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
import ltechnologies.onionphone.securemessenger.core.security.EncryptedCredentialStore
import ltechnologies.onionphone.securemessenger.core.security.MessageSanitizer
import ltechnologies.onionphone.securemessenger.data.MessengerRepository
import org.json.JSONArray
import org.json.JSONObject
import org.signal.core.util.UuidUtil
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.groups.GroupSessionBuilder
import org.signal.libsignal.protocol.message.DecryptionErrorMessage
import org.signal.libsignal.protocol.message.SenderKeyDistributionMessage
import org.whispersystems.signalservice.api.SignalServiceMessageReceiver
import org.whispersystems.signalservice.api.SignalServiceMessageSender
import org.whispersystems.signalservice.api.crypto.AttachmentCipherInputStream
import org.whispersystems.signalservice.api.crypto.EnvelopeMetadata
import org.whispersystems.signalservice.api.crypto.SealedSenderAccess
import org.whispersystems.signalservice.api.crypto.SignalGroupSessionBuilder
import org.whispersystems.signalservice.api.crypto.SignalServiceCipherResult
import org.whispersystems.signalservice.api.messages.EnvelopeResponse
import org.whispersystems.signalservice.api.messages.calls.BusyMessage
import org.whispersystems.signalservice.api.messages.calls.SignalServiceCallMessage
import org.whispersystems.signalservice.api.messages.multidevice.DeviceContactsInputStream
import org.whispersystems.signalservice.api.push.SignalServiceAddress
import org.whispersystems.signalservice.api.util.AttachmentPointerUtil
import org.whispersystems.signalservice.internal.push.AttachmentPointer
import org.whispersystems.signalservice.internal.push.CallMessage
import org.whispersystems.signalservice.internal.push.ConversationIdentifier
import org.whispersystems.signalservice.internal.push.DataMessage
import org.whispersystems.signalservice.internal.push.EditMessage
import org.whispersystems.signalservice.internal.push.Envelope
import org.whispersystems.signalservice.internal.push.ReceiptMessage
import org.whispersystems.signalservice.internal.push.StoryMessage
import org.whispersystems.signalservice.internal.push.SyncMessage
import org.whispersystems.signalservice.internal.push.TypingMessage
import org.whispersystems.signalservice.internal.push.Verified
import timber.log.Timber
import okio.ByteString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Decrypts envelopes and maps Signal content onto Room + credential store.
 * Covers data / sync (sent, contacts, configuration, keys, blocked, read) /
 * typing / receipts — the full secondary-device sync surface we support.
 */
internal class SignalMessageHandler(
    private val context: Context,
    private val accountId: String,
    private val localAci: String,
    private val repository: MessengerRepository,
    private val credentialStore: EncryptedCredentialStore,
    private val cipher: org.whispersystems.signalservice.api.crypto.SignalServiceCipher,
    private val authWebSocket: org.whispersystems.signalservice.api.websocket.SignalWebSocket.AuthenticatedWebSocket,
    private val messageReceiver: SignalServiceMessageReceiver,
    private val groupHelper: SignalGroupHelper,
    private val protocolStore: ltechnologies.onionphone.securemessenger.protocol.signal.store.AndroidSignalProtocolStore,
    private val messageSender: SignalServiceMessageSender,
    private val onTyping: (conversationId: String, peerLabel: String, started: Boolean) -> Unit = { _, _, _ -> },
    private val onContactsSynced: (count: Int) -> Unit = {},
    private val onKeysSynced: () -> Unit = {},
    private val onFetchLatest: (SyncMessage.FetchLatest.Type?) -> Unit = {},
) {
    private val ioScope = CoroutineScope(Dispatchers.IO)
    suspend fun processBatch(batch: List<EnvelopeResponse>) {
        for ((index, response) in batch.withIndex()) {
            try {
                processEnvelope(response)
                sendAckSafely(response, index, batch.size)
            } catch (e: Exception) {
                Timber.w(e, "Failed to process Signal envelope $index/${batch.size}")
                if (isPoisonEnvelope(e)) {
                    sendAckSafely(response, index, batch.size)
                }
            }
        }
    }

    private suspend fun processEnvelope(response: EnvelopeResponse) {
        val envelope = response.envelope
        if (envelope.content == null) return
        val result = cipher.decrypt(envelope, response.serverDeliveredTimestamp) ?: return
        handleContent(result, envelope)
    }

    private suspend fun handleContent(result: SignalServiceCipherResult, envelope: Envelope) {
        val content = result.content
        // SKDM may coexist with a dataMessage — process first so group decrypts work.
        content.senderKeyDistributionMessage?.let { skdm ->
            handleSenderKeyDistribution(skdm, result.metadata)
        }
        when {
            content.dataMessage != null ->
                handleDataMessage(content.dataMessage!!, result.metadata, envelope)
            content.editMessage != null ->
                handleEditMessage(content.editMessage!!, result.metadata, envelope)
            content.syncMessage != null ->
                handleSyncMessage(content.syncMessage!!)
            content.typingMessage != null ->
                handleTyping(content.typingMessage!!, result.metadata)
            content.receiptMessage != null ->
                handleReceipt(content.receiptMessage!!, result.metadata)
            content.decryptionErrorMessage != null ->
                handleDecryptionError(content.decryptionErrorMessage!!, result.metadata)
            content.nullMessage != null ->
                Timber.d("Signal nullMessage (keepalive) for $accountId")
            content.callMessage != null ->
                handleCallMessage(content.callMessage!!, result.metadata)
            content.storyMessage != null ->
                handleStoryMessage(content.storyMessage!!, result.metadata, envelope)
            content.senderKeyDistributionMessage != null ->
                Unit // already handled
            else -> Timber.d("Ignoring unsupported Signal content type")
        }
    }

    private suspend fun handleCallMessage(call: CallMessage, metadata: EnvelopeMetadata) {
        // ICE/opaque are media-path noise without RingRTC — skip spam.
        if (call.offer == null && call.answer == null && call.hangup == null && call.busy == null) {
            Timber.d("Ignoring Signal call ICE/opaque for %s", metadata.sourceServiceId)
            return
        }
        val remoteId = metadata.sourceE164?.takeIf { it.isNotBlank() }
            ?: metadata.sourceServiceId.toString()
        val conversationId = signalConversationId(accountId, remoteId)
        val (body, event, callId, media) = when {
            call.offer != null -> {
                val offer = call.offer!!
                val video = offer.type == CallMessage.Offer.Type.OFFER_VIDEO_CALL
                val mediaLabel = if (video) "video" else "audio"
                val label = if (video) "Appel vidéo entrant" else "Appel audio entrant"
                CallEvent(label, "offer", offer.id ?: 0L, mediaLabel)
            }
            call.answer != null ->
                CallEvent("Appel accepté", "answer", call.answer!!.id ?: 0L, null)
            call.hangup != null ->
                CallEvent("Appel terminé", "hangup", call.hangup!!.id ?: 0L, null)
            call.busy != null ->
                CallEvent("Occupé", "busy", call.busy!!.id ?: 0L, null)
            else -> return
        }
        val now = System.currentTimeMillis()
        val messageId = "${conversationId}_call_${event}_${callId}_$now"
        repository.upsertConversation(
            Conversation(
                id = conversationId,
                protocol = ProtocolId.SIGNAL,
                accountId = accountId,
                remoteId = remoteId,
                title = metadata.sourceE164 ?: remoteId,
                lastMessagePreview = body,
                lastMessageAt = now,
                unreadCount = 1,
            ),
        )
        repository.upsertMessage(
            Message(
                id = messageId,
                conversationId = conversationId,
                protocol = ProtocolId.SIGNAL,
                body = body,
                timestamp = now,
                direction = MessageDirection.INCOMING,
                deliveryState = DeliveryState.DELIVERED,
                senderDisplayName = metadata.sourceE164 ?: metadata.sourceServiceId.toString(),
                kind = MessageKind.CALL,
                payloadJson = JSONObject()
                    .put("type", event)
                    .put("callId", callId)
                    .put("media", media ?: JSONObject.NULL)
                    .toString(),
            ),
        )
        // Without RingRTC we cannot answer — auto-busy so the peer stops ringing.
        if (event == "offer" && callId != 0L) {
            val destDevice = call.destinationDeviceId ?: metadata.sourceDeviceId
            ioScope.launch {
                runCatching {
                    val address = SignalServiceAddress(metadata.sourceServiceId)
                    messageSender.sendCallMessage(
                        address,
                        SealedSenderAccess.NONE,
                        SignalServiceCallMessage.forBusy(BusyMessage(callId), destDevice),
                    )
                    Timber.i("Auto-busy sent for Signal call %s from %s", callId, remoteId)
                }.onFailure { Timber.w(it, "Failed to auto-busy Signal call %s", callId) }
            }
        }
    }

    private data class CallEvent(
        val body: String,
        val event: String,
        val callId: Long,
        val media: String?,
    )

    private suspend fun handleStoryMessage(
        story: StoryMessage,
        metadata: EnvelopeMetadata,
        envelope: Envelope,
    ) {
        val sourceAci = metadata.sourceServiceId.toString()
        val remoteId = "story:$sourceAci"
        val conversationId = signalConversationId(accountId, remoteId)
        val text = story.textAttachment?.text?.takeIf { it.isNotBlank() }
        val body = when {
            text != null -> text
            story.fileAttachment != null -> "📷 Story"
            else -> "Story"
        }
        val now = envelope.clientTimestamp?.takeIf { it > 0 }
            ?: envelope.serverTimestamp?.takeIf { it > 0 }
            ?: System.currentTimeMillis()
        val messageId = envelope.serverGuid?.let { UuidUtil.parseOrNull(it)?.toString() }
            ?: "${conversationId}_$now"
        if (repository.getMessage(messageId) != null) return
        repository.upsertConversation(
            Conversation(
                id = conversationId,
                protocol = ProtocolId.SIGNAL,
                accountId = accountId,
                remoteId = remoteId,
                title = "Story · ${metadata.sourceE164 ?: sourceAci.take(8)}",
                lastMessagePreview = body,
                lastMessageAt = now,
                unreadCount = 1,
            ),
        )
        repository.upsertMessage(
            Message(
                id = messageId,
                conversationId = conversationId,
                protocol = ProtocolId.SIGNAL,
                body = body,
                timestamp = now,
                direction = MessageDirection.INCOMING,
                deliveryState = DeliveryState.DELIVERED,
                senderDisplayName = metadata.sourceE164 ?: sourceAci,
                kind = MessageKind.STORY,
                payloadJson = JSONObject()
                    .put("allowsReplies", story.allowsReplies == true)
                    .put("hasFile", story.fileAttachment != null)
                    .toString(),
            ),
        )
    }

    private suspend fun handleSyncMessage(sync: SyncMessage) {
        sync.sent?.let { handleSentTranscript(it) }
        sync.contacts?.let { handleContactsSync(it) }
        sync.configuration?.let { handleConfiguration(it) }
        sync.keys?.let {
            handleKeys(it)
            onKeysSynced()
        }
        sync.blocked?.let { handleBlocked(it) }
        if (sync.read.isNotEmpty()) {
            handleReadSync(sync.read)
        }
        if (sync.viewed.isNotEmpty()) {
            handleViewedSync(sync.viewed)
        }
        sync.deleteForMe?.let { handleDeleteForMe(it) }
        sync.fetchLatest?.let { fetch ->
            Timber.i("Signal fetchLatest type=%s", fetch.type)
            onFetchLatest(fetch.type)
        }
        sync.verified?.let { handleVerified(it) }
        sync.deviceNameChange?.let { change ->
            credentialStore.put(
                accountId,
                SignalCredentialKeys.LAST_DEVICE_NAME_CHANGE,
                (change.deviceId ?: -1).toString(),
            )
            Timber.i("Signal deviceNameChange deviceId=%s", change.deviceId)
        }
        sync.messageRequestResponse?.let {
            Timber.d("Signal messageRequestResponse received")
        }
        sync.usernameChange?.let {
            Timber.d("Signal usernameChange received")
        }
    }

    private fun handleTyping(typing: TypingMessage, metadata: EnvelopeMetadata) {
        val remoteId = metadata.sourceE164?.takeIf { it.isNotBlank() }
            ?: metadata.sourceServiceId.toString()
        val conversationId = signalConversationId(accountId, remoteId)
        val label = metadata.sourceE164 ?: remoteId
        val started = typing.action == TypingMessage.Action.STARTED
        onTyping(conversationId, label, started)
    }

    private suspend fun handleReceipt(receipt: ReceiptMessage, metadata: EnvelopeMetadata) {
        val remoteId = metadata.sourceE164?.takeIf { it.isNotBlank() }
            ?: metadata.sourceServiceId.toString()
        val conversationId = signalConversationId(accountId, remoteId)
        val targetState = when (receipt.type) {
            ReceiptMessage.Type.READ -> DeliveryState.READ
            ReceiptMessage.Type.DELIVERY -> DeliveryState.DELIVERED
            else -> return
        }
        val timestamps = receipt.timestamp.toSet()
        if (timestamps.isEmpty()) return
        val messages = repository.listMessagesPage(conversationId, limit = 200, offset = 0)
        for (message in messages) {
            if (message.direction != MessageDirection.OUTGOING) continue
            if (message.timestamp !in timestamps) continue
            if (message.deliveryState.ordinal >= targetState.ordinal) continue
            repository.upsertMessage(message.copy(deliveryState = targetState))
        }
    }

    private fun handleConfiguration(config: SyncMessage.Configuration) {
        config.readReceipts?.let {
            credentialStore.put(accountId, SignalCredentialKeys.CONFIG_READ_RECEIPTS, it.toString())
        }
        config.typingIndicators?.let {
            credentialStore.put(accountId, SignalCredentialKeys.CONFIG_TYPING_INDICATORS, it.toString())
        }
        config.linkPreviews?.let {
            credentialStore.put(accountId, SignalCredentialKeys.CONFIG_LINK_PREVIEWS, it.toString())
        }
        config.unidentifiedDeliveryIndicators?.let {
            credentialStore.put(accountId, SignalCredentialKeys.CONFIG_UDI, it.toString())
        }
        Timber.i(
            "Signal configuration sync: readReceipts=%s typing=%s linkPreviews=%s",
            config.readReceipts,
            config.typingIndicators,
            config.linkPreviews,
        )
    }

    private fun handleKeys(keys: SyncMessage.Keys) {
        keys.accountEntropyPool?.takeIf { it.isNotBlank() }?.let {
            credentialStore.put(accountId, SignalCredentialKeys.ACCOUNT_ENTROPY_POOL, it)
        }
        keys.mediaRootBackupKey?.takeIf { it.size > 0 }?.let { bytes ->
            credentialStore.put(
                accountId,
                SignalCredentialKeys.MEDIA_ROOT_BACKUP_KEY,
                Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP),
            )
        }
        Timber.i(
            "Signal keys sync: aep=%s mediaRoot=%s",
            keys.accountEntropyPool != null,
            keys.mediaRootBackupKey != null,
        )
    }

    private fun handleBlocked(blocked: SyncMessage.Blocked) {
        val json = JSONObject()
        json.put("numbers", JSONArray(blocked.numbers))
        json.put("acis", JSONArray(blocked.acis))
        json.put(
            "groupIds",
            JSONArray(
                blocked.groupIds.map {
                    Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP)
                },
            ),
        )
        credentialStore.put(accountId, SignalCredentialKeys.BLOCKED_LIST, json.toString())
        Timber.i(
            "Signal blocked sync: numbers=%d acis=%d groups=%d",
            blocked.numbers.size,
            blocked.acis.size,
            blocked.groupIds.size,
        )
    }

    private suspend fun handleReadSync(reads: List<SyncMessage.Read>) {
        for (read in reads) {
            val sender = read.senderAci?.takeIf { it.isNotBlank() } ?: continue
            val timestamp = read.timestamp ?: continue
            val conversationId = signalConversationId(accountId, sender)
            val existing = repository.getConversation(conversationId)
            if (existing != null && existing.unreadCount > 0) {
                repository.upsertConversation(existing.copy(unreadCount = 0))
            }
            val messages = repository.listMessagesPage(conversationId, limit = 100, offset = 0)
            messages.firstOrNull {
                it.direction == MessageDirection.INCOMING && it.timestamp == timestamp
            }?.let { message ->
                if (message.deliveryState != DeliveryState.READ) {
                    repository.upsertMessage(message.copy(deliveryState = DeliveryState.READ))
                }
            }
        }
        Timber.i("Signal read sync: applied %d entries", reads.size)
    }

    private suspend fun handleViewedSync(viewed: List<SyncMessage.Viewed>) {
        for (entry in viewed) {
            val sender = entry.senderAci?.takeIf { it.isNotBlank() } ?: continue
            val timestamp = entry.timestamp ?: continue
            val conversationId = signalConversationId(accountId, sender)
            for (message in repository.listMessagesByTimestamp(conversationId, timestamp)) {
                if (message.deliveryState != DeliveryState.READ) {
                    repository.upsertMessage(message.copy(deliveryState = DeliveryState.READ))
                }
            }
            repository.getConversation(conversationId)?.let { conv ->
                if (conv.unreadCount > 0) {
                    repository.upsertConversation(conv.copy(unreadCount = 0))
                }
            }
        }
        Timber.i("Signal viewed sync: applied %d entries", viewed.size)
    }

    private fun handleSenderKeyDistribution(raw: ByteString, metadata: EnvelopeMetadata) {
        try {
            val skdm = SenderKeyDistributionMessage(raw.toByteArray())
            val sender = SignalProtocolAddress(
                metadata.sourceServiceId.toString(),
                metadata.sourceDeviceId,
            )
            SignalGroupSessionBuilder(
                SignalSessionLockImpl,
                GroupSessionBuilder(protocolStore.aci()),
            ).process(sender, skdm)
            Timber.d("Processed SKDM from %s device=%d", sender.name, sender.deviceId)
        } catch (e: Exception) {
            Timber.w(e, "Failed to process SenderKeyDistributionMessage")
        }
    }

    private fun handleDecryptionError(raw: ByteString, metadata: EnvelopeMetadata) {
        try {
            val dem = DecryptionErrorMessage(raw.toByteArray())
            val address = SignalProtocolAddress(
                metadata.sourceServiceId.toString(),
                dem.deviceId,
            )
            if (protocolStore.aci().containsSession(address)) {
                protocolStore.aci().deleteSession(address)
                Timber.i(
                    "DEM: deleted session for %s device=%d ts=%d",
                    address.name,
                    dem.deviceId,
                    dem.timestamp,
                )
            } else {
                Timber.i(
                    "DEM: no session for %s device=%d ts=%d",
                    address.name,
                    dem.deviceId,
                    dem.timestamp,
                )
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to handle DecryptionErrorMessage")
        }
    }

    private fun handleVerified(verified: Verified) {
        val aci = verified.destinationAci?.takeIf { it.isNotBlank() }
            ?: verified.destinationAciBinary?.takeIf { it.size > 0 }?.let { bytes ->
                runCatching { org.signal.core.models.ServiceId.ACI.parseOrThrow(bytes.toByteArray()).toString() }
                    .getOrNull()
            }
            ?: return
        val keyBytes = verified.identityKey?.takeIf { it.size > 0 }?.toByteArray() ?: return
        try {
            val identity = IdentityKey(keyBytes)
            val address = SignalProtocolAddress(aci, 1)
            protocolStore.aci().saveIdentity(address, identity)
            credentialStore.put(
                accountId,
                "verified:$aci",
                (verified.state?.value ?: -1).toString(),
            )
            Timber.i("Signal verified sync for %s state=%s", aci, verified.state)
        } catch (e: Exception) {
            Timber.w(e, "Failed to apply verified sync for %s", aci)
        }
    }

    private suspend fun handleEditMessage(
        edit: EditMessage,
        metadata: EnvelopeMetadata,
        envelope: Envelope,
    ) {
        val dataMessage = edit.dataMessage ?: return
        val targetTs = edit.targetSentTimestamp ?: return
        val groupMasterKey = dataMessage.groupV2?.masterKey
        val isGroup = groupMasterKey != null && groupMasterKey.size > 0
        val remoteId = when {
            isGroup -> "gv2:" + Base64.encodeToString(groupMasterKey!!.toByteArray(), Base64.NO_WRAP)
            else -> metadata.sourceE164?.takeIf { it.isNotBlank() }
                ?: metadata.sourceServiceId.toString()
        }
        val conversationId = signalConversationId(accountId, remoteId)
        val body = dataMessage.body?.trim().orEmpty()
        val targets = repository.listMessagesByTimestamp(conversationId, targetTs)
        if (targets.isEmpty()) {
            // Target missing locally — store as a normal inbound message with edit payload.
            handleDataMessage(
                dataMessage,
                metadata,
                envelope,
            )
            return
        }
        val (kind, payloadJson, expireSeconds) = SignalFeatureHelpers.classifyDataMessage(dataMessage, false)
        val editPayload = JSONObject(payloadJson ?: "{}")
            .put("edited", true)
            .put("targetSentTimestamp", targetTs)
            .toString()
        for (target in targets) {
            repository.upsertMessage(
                target.copy(
                    body = MessageSanitizer.sanitize(body.ifBlank { target.body }).value,
                    kind = kind,
                    payloadJson = editPayload,
                    expireSeconds = expireSeconds ?: target.expireSeconds,
                ),
            )
        }
        repository.getConversation(conversationId)?.let { conv ->
            repository.upsertConversation(
                conv.copy(
                    lastMessagePreview = MessageSanitizer.sanitize(body.ifBlank { conv.lastMessagePreview.orEmpty() }).value,
                    lastMessageAt = dataMessage.timestamp ?: System.currentTimeMillis(),
                ),
            )
        }
        Timber.i("Signal edit applied targetTs=%d conversation=%s", targetTs, conversationId)
    }

    private suspend fun handleDeleteForMe(deleteForMe: SyncMessage.DeleteForMe) {
        for (messageDeletes in deleteForMe.messageDeletes) {
            val conversationId = resolveConversationId(messageDeletes.conversation) ?: continue
            val timestamps = messageDeletes.messages.mapNotNull { it.sentTimestamp }.toSet()
            if (timestamps.isEmpty()) continue
            val ids = repository.listMessagesByTimestamps(conversationId, timestamps).map { it.id }
            repository.deleteMessages(ids)
            Timber.d("deleteForMe messages=%d conversation=%s", ids.size, conversationId)
        }
        for (conversationDelete in deleteForMe.conversationDeletes) {
            val conversationId = resolveConversationId(conversationDelete.conversation) ?: continue
            if (conversationDelete.isFullDelete == true) {
                repository.deleteConversation(conversationId)
                Timber.d("deleteForMe full conversation %s", conversationId)
            } else {
                val timestamps = (
                    conversationDelete.mostRecentMessages +
                        conversationDelete.mostRecentNonExpiringMessages
                    ).mapNotNull { it.sentTimestamp }.toSet()
                if (timestamps.isNotEmpty()) {
                    val ids = repository.listMessagesByTimestamps(conversationId, timestamps).map { it.id }
                    repository.deleteMessages(ids)
                } else {
                    repository.deleteConversationMessages(conversationId)
                }
                Timber.d("deleteForMe conversation prune %s", conversationId)
            }
        }
        if (deleteForMe.localOnlyConversationDeletes.isNotEmpty()) {
            for (localOnly in deleteForMe.localOnlyConversationDeletes) {
                val conversationId = resolveConversationId(localOnly.conversation) ?: continue
                repository.deleteConversation(conversationId)
            }
        }
    }

    private suspend fun resolveConversationId(identifier: ConversationIdentifier?): String? {
        if (identifier == null) return null
        val remoteId = when {
            !identifier.threadServiceId.isNullOrBlank() -> identifier.threadServiceId!!
            identifier.threadServiceIdBinary != null && identifier.threadServiceIdBinary!!.size > 0 ->
                runCatching {
                    org.signal.core.models.ServiceId.ACI.parseOrThrow(
                        identifier.threadServiceIdBinary!!.toByteArray(),
                    ).toString()
                }.getOrNull()
            !identifier.threadE164.isNullOrBlank() -> identifier.threadE164!!
            identifier.threadGroupId != null && identifier.threadGroupId!!.size > 0 -> {
                val target = identifier.threadGroupId!!.toByteArray()
                return repository.listConversationsForAccount(accountId).firstOrNull { conv ->
                    val masterKey = SignalGroupHelper.parseMasterKey(conv.remoteId) ?: return@firstOrNull false
                    runCatching {
                        val params = org.signal.libsignal.zkgroup.groups.GroupSecretParams
                            .deriveFromMasterKey(
                                org.signal.libsignal.zkgroup.groups.GroupMasterKey(masterKey),
                            )
                        params.publicParams.groupIdentifier.serialize().contentEquals(target)
                    }.getOrDefault(false)
                }?.id
            }
            else -> null
        } ?: return null
        return signalConversationId(accountId, remoteId)
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
            val name = sanitizeFileName(pointer.fileName ?: "attachment_$index")
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
                val claimed = pointer.size?.toLong() ?: DEFAULT_MAX_ATTACHMENT_BYTES
                val maxSize = claimed.coerceIn(1L, DEFAULT_MAX_ATTACHMENT_BYTES)
                messageReceiver.retrieveAttachment(servicePointer, tmp, maxSize, integrity).use { input ->
                    val outFile = File(destDir, "${id}_$name")
                    require(outFile.canonicalFile.parentFile?.canonicalPath == destDir.canonicalPath) {
                        "Attachment path escaped media directory"
                    }
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

    private suspend fun handleSentTranscript(sent: SyncMessage.Sent) {
        sent.storyMessage?.let { story ->
            val text = story.textAttachment?.text?.takeIf { it.isNotBlank() }
            val body = text ?: if (story.fileAttachment != null) "📷 Story" else "Story"
            val remoteId = "story:my"
            val conversationId = signalConversationId(accountId, remoteId)
            val timestamp = sent.timestamp ?: System.currentTimeMillis()
            repository.upsertConversation(
                Conversation(
                    id = conversationId,
                    protocol = ProtocolId.SIGNAL,
                    accountId = accountId,
                    remoteId = remoteId,
                    title = "My Story",
                    lastMessagePreview = body,
                    lastMessageAt = timestamp,
                ),
            )
            repository.upsertMessage(
                Message(
                    id = "${conversationId}_$timestamp",
                    conversationId = conversationId,
                    protocol = ProtocolId.SIGNAL,
                    body = body,
                    timestamp = timestamp,
                    direction = MessageDirection.OUTGOING,
                    deliveryState = DeliveryState.SENT,
                    kind = MessageKind.STORY,
                ),
            )
            return
        }
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
                title = if (isGroup) {
                    groupHelper.cachedTitle(groupMasterKey!!.toByteArray()) ?: "Groupe Signal"
                } else {
                    destination
                },
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

    private suspend fun handleContactsSync(contactsSync: SyncMessage.Contacts) {
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
            seedConversationsFromContacts(parsed)
            credentialStore.put(accountId, SignalCredentialKeys.INITIAL_SYNC_DONE, "true")
            onContactsSynced(parsed.size)
        }
        Timber.i("Signal contacts sync: stored %d contacts (complete=%s)", parsed.size, contactsSync.complete)
    }

    private suspend fun seedConversationsFromContacts(contacts: List<Contact>) {
        for (contact in contacts) {
            val conversationId = signalConversationId(accountId, contact.remoteId)
            val existing = repository.getConversation(conversationId)
            if (existing != null) {
                if (existing.title != contact.displayName && contact.displayName.isNotBlank()) {
                    repository.upsertConversation(existing.copy(title = contact.displayName))
                }
                continue
            }
            repository.upsertConversation(
                Conversation(
                    id = conversationId,
                    protocol = ProtocolId.SIGNAL,
                    accountId = accountId,
                    remoteId = contact.remoteId,
                    title = contact.displayName.ifBlank { contact.remoteId },
                    lastMessagePreview = null,
                    lastMessageAt = 0L,
                    unreadCount = 0,
                ),
            )
        }
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

        private fun sanitizeFileName(raw: String): String {
            val base = raw.substringAfterLast('/').substringAfterLast('\\')
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .take(120)
            return base.ifBlank { "attachment" }
        }

        private fun isPoisonEnvelope(e: Exception): Boolean {
            val name = e::class.java.name
            return name.contains("InvalidMessage") ||
                name.contains("DuplicateMessage") ||
                name.contains("LegacyMessage") ||
                name.contains("InvalidVersion") ||
                name.contains("UntrustedIdentity") ||
                name.contains("NoSession") ||
                name.contains("ProtocolInvalid")
        }
    }
}
