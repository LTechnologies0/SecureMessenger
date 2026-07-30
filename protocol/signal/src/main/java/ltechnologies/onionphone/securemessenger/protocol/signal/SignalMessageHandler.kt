package ltechnologies.onionphone.securemessenger.protocol.signal

import android.content.Context
import android.util.Base64
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import ltechnologies.onionphone.securemessenger.core.model.Attachment
import ltechnologies.onionphone.securemessenger.core.model.AttachmentState
import ltechnologies.onionphone.securemessenger.core.model.Contact
import ltechnologies.onionphone.securemessenger.core.model.Conversation
import ltechnologies.onionphone.securemessenger.core.model.DeliveryState
import ltechnologies.onionphone.securemessenger.core.model.Message
import ltechnologies.onionphone.securemessenger.core.model.MessageDirection
import ltechnologies.onionphone.securemessenger.core.model.MessageKind
import ltechnologies.onionphone.securemessenger.core.model.ProtocolId
import ltechnologies.onionphone.securemessenger.core.security.MessageSanitizer
import ltechnologies.onionphone.securemessenger.data.MessengerRepository
import org.signal.core.util.UuidUtil
import org.whispersystems.signalservice.api.SignalServiceMessageReceiver
import org.whispersystems.signalservice.api.crypto.AttachmentCipherInputStream
import org.whispersystems.signalservice.api.crypto.EnvelopeMetadata
import org.whispersystems.signalservice.api.crypto.SignalServiceCipherResult
import org.whispersystems.signalservice.api.messages.EnvelopeResponse
import org.whispersystems.signalservice.api.messages.multidevice.DeviceContactsInputStream
import org.whispersystems.signalservice.api.util.AttachmentPointerUtil
import org.whispersystems.signalservice.internal.push.AttachmentPointer
import org.whispersystems.signalservice.internal.push.DataMessage
import org.whispersystems.signalservice.internal.push.Envelope
import timber.log.Timber

internal class SignalMessageHandler(
    private val context: Context,
    private val accountId: String,
    private val localAci: String,
    private val repository: MessengerRepository,
    private val cipher: org.whispersystems.signalservice.api.crypto.SignalServiceCipher,
    private val authWebSocket: org.whispersystems.signalservice.api.websocket.SignalWebSocket.AuthenticatedWebSocket,
    private val messageReceiver: SignalServiceMessageReceiver,
    private val groupHelper: SignalGroupHelper,
) {
    suspend fun processBatch(batch: List<EnvelopeResponse>) {
        for ((index, response) in batch.withIndex()) {
            try {
                processEnvelope(response)
                sendAckSafely(response, index, batch.size)
            } catch (e: Exception) {
                Timber.w(e, "Failed to process Signal envelope $index/${batch.size}")
                sendAckSafely(response, index, batch.size)
            }
        }
    }

    private suspend fun processEnvelope(response: EnvelopeResponse) {
        val envelope = response.envelope
        if (envelope.content == null) {
            return
        }
        val result = cipher.decrypt(envelope, response.serverDeliveredTimestamp) ?: return
        handleContent(result, envelope)
    }

    private suspend fun handleContent(result: SignalServiceCipherResult, envelope: Envelope) {
        val content = result.content
        when {
            content.dataMessage != null -> handleDataMessage(content.dataMessage!!, result.metadata, envelope)
            content.syncMessage?.sent != null -> handleSentTranscript(content.syncMessage!!.sent!!)
            content.syncMessage?.contacts != null -> handleContactsSync(content.syncMessage!!.contacts!!)
            else -> Timber.d("Ignoring unsupported Signal content type")
        }
    }

    private suspend fun handleDataMessage(
        dataMessage: DataMessage,
        metadata: EnvelopeMetadata,
        envelope: Envelope,
    ) {
        val body = dataMessage.body?.trim().orEmpty()
        val groupMasterKey = dataMessage.groupV2?.masterKey
        val isGroup = groupMasterKey != null && groupMasterKey.size > 0
        val remoteId = when {
            isGroup -> "gv2:" + Base64.encodeToString(groupMasterKey!!.toByteArray(), Base64.NO_WRAP)
            else -> metadata.sourceE164?.takeIf { it.isNotBlank() }
                ?: metadata.sourceServiceId.toString()
        }
        if (isGroup && groupMasterKey != null) {
            groupHelper.rememberMember(groupMasterKey.toByteArray(), metadata.sourceServiceId.toString())
            dataMessage.groupV2?.revision?.let { groupHelper.rememberRevision(groupMasterKey.toByteArray(), it) }
        }

        val attachmentPointers = dataMessage.attachments.toMutableList()
        dataMessage.sticker?.data_?.let { attachmentPointers.add(it) }
        val downloaded = downloadAttachments(attachmentPointers, envelope.serverGuid)
        val hasVoice = downloaded.any { it.mimeType.startsWith("audio/") } ||
            dataMessage.attachments.any { ((it.flags ?: 0) and AttachmentPointer.Flags.VOICE_MESSAGE.value) != 0 }

        if (body.isEmpty() &&
            downloaded.isEmpty() &&
            dataMessage.groupV2?.groupChange == null &&
            !SignalFeatureHelpers.hasRichContent(dataMessage)
        ) {
            return
        }

        val (kind, payloadJson, expireSeconds) = SignalFeatureHelpers.classifyDataMessage(dataMessage, hasVoice)
        val conversationId = signalConversationId(accountId, remoteId)
        val timestamp = dataMessage.timestamp ?: envelope.clientTimestamp ?: envelope.serverTimestamp
            ?: System.currentTimeMillis()
        val messageId = envelope.serverGuid?.let { UuidUtil.parseOrNull(it)?.toString() }
            ?: "${conversationId}_$timestamp"
        val preview = when (kind) {
            MessageKind.STICKER -> dataMessage.sticker?.emoji ?: "⭐"
            MessageKind.POLL -> dataMessage.pollCreate?.question ?: "Sondage"
            MessageKind.CONTACT -> "Contact"
            MessageKind.VOICE -> "🎤"
            MessageKind.SYSTEM -> dataMessage.reaction?.emoji ?: body.ifBlank { "System" }
            else -> body.ifBlank {
                downloaded.firstOrNull()?.fileName
                    ?: if (isGroup) "Groupe Signal" else "📎"
            }
        }
        val title = if (isGroup) {
            groupHelper.cachedTitle(groupMasterKey!!.toByteArray()) ?: "Groupe Signal"
        } else {
            remoteId
        }

        repository.upsertConversation(
            Conversation(
                id = conversationId,
                protocol = ProtocolId.SIGNAL,
                accountId = accountId,
                remoteId = remoteId,
                title = title,
                lastMessagePreview = MessageSanitizer.sanitize(preview).value,
                lastMessageAt = timestamp,
                unreadCount = 1,
            ),
        )
        if (body.isNotEmpty() || downloaded.isNotEmpty() || SignalFeatureHelpers.hasRichContent(dataMessage)) {
            repository.upsertMessage(
                Message(
                    id = messageId,
                    conversationId = conversationId,
                    protocol = ProtocolId.SIGNAL,
                    body = MessageSanitizer.sanitize(body.ifBlank { preview }).value,
                    timestamp = timestamp,
                    direction = MessageDirection.INCOMING,
                    deliveryState = DeliveryState.DELIVERED,
                    senderDisplayName = metadata.sourceE164 ?: metadata.sourceServiceId.toString(),
                    attachments = downloaded,
                    kind = kind,
                    payloadJson = payloadJson,
                    expireSeconds = expireSeconds,
                ),
            )
        }
    }

    private fun downloadAttachments(
        pointers: List<AttachmentPointer>,
        serverGuid: String?,
    ): List<Attachment> {
        if (pointers.isEmpty()) return emptyList()
        val destDir = File(context.filesDir, "signal_media_$accountId").also { it.mkdirs() }
        return pointers.mapIndexed { index, pointer ->
            val id = "${serverGuid ?: System.currentTimeMillis()}_att_$index"
            val mime = pointer.contentType ?: "application/octet-stream"
            val name = pointer.fileName ?: "attachment_$index"
            try {
                val key = pointer.key?.toByteArray()
                val digestBytes = pointer.digest?.toByteArray()
                if (key == null || digestBytes == null) {
                    return@mapIndexed Attachment(
                        id = id,
                        mimeType = mime,
                        fileName = name,
                        remoteRef = pointer.cdnKey ?: pointer.cdnId?.toString(),
                        sizeBytes = pointer.size?.toLong() ?: 0L,
                        state = AttachmentState.PENDING,
                    )
                }
                val servicePointer = AttachmentPointerUtil.createSignalAttachmentPointer(pointer)
                val tmp = File(destDir, "$id.tmp")
                val integrity = AttachmentCipherInputStream.IntegrityCheck.forEncryptedDigest(digestBytes)
                val maxSize = (pointer.size?.toLong() ?: DEFAULT_MAX_ATTACHMENT_BYTES).coerceAtLeast(1L)
                messageReceiver.retrieveAttachment(servicePointer, tmp, maxSize, integrity).use { input ->
                    val outFile = File(destDir, "${id}_$name")
                    FileOutputStream(outFile).use { output -> input.copyTo(output) }
                    Attachment(
                        id = id,
                        mimeType = mime,
                        fileName = name,
                        localPath = outFile.absolutePath,
                        remoteRef = pointer.cdnKey ?: pointer.cdnId?.toString(),
                        sizeBytes = outFile.length(),
                        state = AttachmentState.READY,
                    )
                }
            } catch (e: Exception) {
                Timber.w(e, "Signal attachment download failed for $id")
                Attachment(
                    id = id,
                    mimeType = mime,
                    fileName = name,
                    remoteRef = pointer.cdnKey ?: pointer.cdnId?.toString(),
                    sizeBytes = pointer.size?.toLong() ?: 0L,
                    state = AttachmentState.FAILED,
                )
            }
        }
    }

    private suspend fun handleSentTranscript(
        sent: org.whispersystems.signalservice.internal.push.SyncMessage.Sent,
    ) {
        val dataMessage = sent.message ?: return
        val body = dataMessage.body?.trim().orEmpty()
        val groupMasterKey = dataMessage.groupV2?.masterKey
        val isGroup = groupMasterKey != null && groupMasterKey.size > 0
        val destination = when {
            isGroup -> "gv2:" + Base64.encodeToString(groupMasterKey!!.toByteArray(), Base64.NO_WRAP)
            else -> sent.destinationE164?.takeIf { it.isNotBlank() }
                ?: sent.destinationServiceId
                ?: return
        }
        val attachmentPointers = dataMessage.attachments.toMutableList()
        dataMessage.sticker?.data_?.let { attachmentPointers.add(it) }
        val downloaded = downloadAttachments(attachmentPointers, null)
        val hasVoice = downloaded.any { it.mimeType.startsWith("audio/") } ||
            dataMessage.attachments.any { ((it.flags ?: 0) and AttachmentPointer.Flags.VOICE_MESSAGE.value) != 0 }
        if (body.isEmpty() && downloaded.isEmpty() && !SignalFeatureHelpers.hasRichContent(dataMessage)) {
            return
        }
        val (kind, payloadJson, expireSeconds) = SignalFeatureHelpers.classifyDataMessage(dataMessage, hasVoice)
        val conversationId = signalConversationId(accountId, destination)
        val timestamp = dataMessage.timestamp ?: System.currentTimeMillis()
        val preview = body.ifBlank {
            when (kind) {
                MessageKind.STICKER -> dataMessage.sticker?.emoji ?: "⭐"
                MessageKind.POLL -> dataMessage.pollCreate?.question ?: "Sondage"
                MessageKind.VOICE -> "🎤"
                else -> downloaded.firstOrNull()?.fileName ?: "📎"
            }
        }
        repository.upsertConversation(
            Conversation(
                id = conversationId,
                protocol = ProtocolId.SIGNAL,
                accountId = accountId,
                remoteId = destination,
                title = if (isGroup) "Groupe Signal" else destination,
                lastMessagePreview = MessageSanitizer.sanitize(preview).value,
                lastMessageAt = timestamp,
            ),
        )
        repository.upsertMessage(
            Message(
                id = "${conversationId}_$timestamp",
                conversationId = conversationId,
                protocol = ProtocolId.SIGNAL,
                body = MessageSanitizer.sanitize(body.ifBlank { preview }).value,
                timestamp = timestamp,
                direction = MessageDirection.OUTGOING,
                deliveryState = DeliveryState.SENT,
                attachments = downloaded,
                kind = kind,
                payloadJson = payloadJson,
                expireSeconds = expireSeconds,
            ),
        )
    }

    private suspend fun handleContactsSync(
        contactsSync: org.whispersystems.signalservice.internal.push.SyncMessage.Contacts,
    ) {
        val blob = contactsSync.blob ?: return
        val downloaded = downloadAttachments(listOf(blob), "contacts_sync")
        val path = downloaded.firstOrNull()?.localPath ?: return
        val parsed = mutableListOf<Contact>()
        try {
            FileInputStream(File(path)).use { input ->
                val stream = DeviceContactsInputStream(input)
                while (true) {
                    val deviceContact = try {
                        stream.read() ?: break
                    } catch (e: Exception) {
                        Timber.w(e, "Stopping contacts sync parse")
                        break
                    }
                    deviceContact.avatar.orElse(null)?.let { avatar ->
                        runCatching { avatar.inputStream.readBytes() }
                    }
                    val remoteId = deviceContact.aci.map { it.toString() }.orElse(null)
                        ?: deviceContact.e164.orElse(null)
                        ?: continue
                    val displayName = deviceContact.name.orElse(null)
                        ?: deviceContact.e164.orElse(remoteId)
                    parsed += Contact(
                        id = "${accountId}_$remoteId",
                        protocol = ProtocolId.SIGNAL,
                        accountId = accountId,
                        remoteId = remoteId,
                        displayName = displayName,
                        handle = deviceContact.aci.map { it.toString() }.orElse(null),
                        phone = deviceContact.e164.orElse(null),
                    )
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse SyncMessage.Contacts")
            return
        }
        if (contactsSync.complete == true || parsed.isNotEmpty()) {
            repository.replaceContacts(accountId, parsed)
        }
        Timber.i("Signal contacts sync: stored %d contacts (complete=%s)", parsed.size, contactsSync.complete)
    }

    private fun sendAckSafely(response: EnvelopeResponse, index: Int, size: Int) {
        try {
            authWebSocket.sendAck(response)
        } catch (e: Exception) {
            Timber.w(e, "Failed to ack Signal envelope ${index + 1}/$size")
        }
    }

    companion object {
        private const val DEFAULT_MAX_ATTACHMENT_BYTES = 100L * 1024L * 1024L
    }
}
