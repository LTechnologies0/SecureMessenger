package ltechnologies.onionphone.securemessenger.protocol.xmpp

import org.jivesoftware.smack.packet.Message as SmackMessage
import org.jivesoftware.smackx.carbons.packet.CarbonExtension
import org.jivesoftware.smackx.forward.packet.Forwarded
import org.jivesoftware.smackx.mam.MamManager
import org.jxmpp.jid.impl.JidCreate
import ltechnologies.onionphone.securemessenger.core.model.Attachment
import ltechnologies.onionphone.securemessenger.core.model.AttachmentState
import ltechnologies.onionphone.securemessenger.core.model.Conversation
import ltechnologies.onionphone.securemessenger.core.model.DeliveryState
import ltechnologies.onionphone.securemessenger.core.model.Message
import ltechnologies.onionphone.securemessenger.core.model.MessageDirection
import ltechnologies.onionphone.securemessenger.core.model.ProtocolId
import ltechnologies.onionphone.securemessenger.data.MessengerRepository
import timber.log.Timber

/**
 * XEP-0313 MAM archive sync with XEP-0384 OMEMO decryption and XEP-0280 carbon direction.
 *
 * Conversations-style catchup: page with [MamManager.MamQuery.pageNext] until [isComplete]
 * (or [MAX_PAGES]), not a single 50-message shot.
 */
object XmppMamSync {
    private const val DEFAULT_PAGE_SIZE = 50
    private const val MAX_PAGES = 40

    /**
     * @return true if a MAM query was issued against the network (even if zero messages).
     */
    suspend fun syncHistory(
        smack: SmackClientFacade,
        accountId: String,
        repository: MessengerRepository,
        remoteJid: String,
        maxResults: Int = DEFAULT_PAGE_SIZE,
    ): Boolean {
        val mam = smack.mamManager ?: return false
        if (!mam.isSupported) return false
        if (smack.omemoHelper != null && smack.omemoHelper?.ready != true) return false
        val with = JidCreate.entityBareFrom(remoteJid)
        val pageSize = maxResults.coerceIn(10, 100)
        val queryArgsBuilder = MamManager.MamQueryArgs.builder()
            .setResultPageSizeTo(pageSize)
        // Direct chats: filter by peer. MUC rooms: archive is often queried against the room JID.
        queryArgsBuilder.limitResultsToJid(with)
        // Prefer messages after the newest local timestamp when we already have history.
        val convId = XmppProtocol.conversationIdFor(accountId, remoteJid)
        val sinceMs = repository.maxMessageTimestamp(convId)
        if (sinceMs != null && sinceMs > 0L) {
            queryArgsBuilder.limitResultsSince(java.util.Date(sinceMs))
        }
        val query = try {
            mam.queryArchive(queryArgsBuilder.build())
        } catch (e: Exception) {
            Timber.w(e, "MAM queryArchive failed for %s", remoteJid)
            return false
        }
        val myJid = smack.myBareJid()
        val isMuc = SmackClientFacade.isLikelyMucJid(remoteJid) || smack.isMucRoom(remoteJid)
        var pages = 0
        var totalPersisted = 0

        suspend fun ingest(smackMessages: List<SmackMessage>) {
            val messages = smackMessages.mapNotNull { smackMsg ->
                toStoredMessage(smack, accountId, remoteJid, myJid, isMuc, smackMsg)
            }
            if (messages.isEmpty()) return
            totalPersisted += messages.size
            persistMamPage(repository, accountId, remoteJid, messages)
        }

        ingest(query.messages)
        pages++
        while (!query.isComplete && pages < MAX_PAGES) {
            val next = try {
                query.pageNext(pageSize)
            } catch (e: Exception) {
                Timber.w(e, "MAM pageNext failed for %s after %d pages", remoteJid, pages)
                break
            }
            if (next.isEmpty()) break
            ingest(next)
            pages++
        }
        Timber.i(
            "MAM catchup %s pages=%d messages=%d complete=%s",
            remoteJid,
            pages,
            totalPersisted,
            query.isComplete,
        )
        return true
    }

    private suspend fun persistMamPage(
        repository: MessengerRepository,
        accountId: String,
        remoteJid: String,
        messages: List<Message>,
    ) {
        if (messages.isEmpty()) return
        repository.upsertMessages(messages)
        val last = messages.maxBy { it.timestamp }
        repository.upsertConversation(
            Conversation(
                id = last.conversationId,
                protocol = ProtocolId.XMPP,
                accountId = accountId,
                remoteId = remoteJid,
                title = remoteJid,
                lastMessagePreview = last.body.take(100),
                lastMessageAt = last.timestamp,
            ),
        )
    }

    private fun toStoredMessage(
        smack: SmackClientFacade,
        accountId: String,
        remoteJid: String,
        myJid: String?,
        isMuc: Boolean,
        smackMsg: SmackMessage,
    ): Message? {
        val body = resolveBody(smack, remoteJid, smackMsg) ?: return null
        val convId = XmppProtocol.conversationIdFor(accountId, remoteJid)
        val ts = SmackClientFacade.extractDelayTimestamp(smackMsg) ?: System.currentTimeMillis()
        val fromBare = smackMsg.from?.asBareJid()?.toString()
        val outgoing = SmackClientFacade.isCarbonSent(smackMsg) || fromBare == myJid
        val uploadUrl = SmackClientFacade.extractHttpUploadUrl(body)
            ?: SmackClientFacade.extractOobUrl(smackMsg)
        val attachments = uploadUrl?.let { url ->
            val fileName = url.substringAfterLast('/')
                .substringBefore('?')
                .substringBefore('#')
                .takeIf { it.isNotBlank() }
            val destDir = java.io.File(
                smack.filesDir(),
                "xmpp_media/$accountId",
            )
            // Prefer download on this account's session — aesgcm cannot render via remoteRef alone.
            val dest = java.io.File(
                destDir,
                "mam_${smackMsg.stanzaId ?: ts}_${fileName ?: "file"}",
            )
            val local = smack.downloadHttpUploadUrl(url, dest).getOrNull()?.absolutePath
            val needsLocal = url.startsWith("aesgcm://", ignoreCase = true)
            listOf(
                Attachment(
                    id = "${convId}_mam_${smackMsg.stanzaId ?: ts}_file",
                    mimeType = "application/octet-stream",
                    fileName = fileName,
                    remoteRef = url,
                    localPath = local,
                    state = when {
                        local != null -> AttachmentState.READY
                        needsLocal -> AttachmentState.FAILED
                        else -> AttachmentState.READY
                    },
                ),
            )
        } ?: emptyList()
        val displayBody = if (uploadUrl != null) {
            attachments.firstOrNull()?.fileName ?: "File"
        } else {
            body
        }
        val sender = when {
            outgoing -> myJid
            isMuc -> smackMsg.from?.resourceOrNull?.toString() ?: fromBare ?: remoteJid
            else -> remoteJid
        }
        return Message(
            id = "${convId}_mam_${smackMsg.stanzaId ?: ts}",
            conversationId = convId,
            protocol = ProtocolId.XMPP,
            body = displayBody,
            timestamp = ts,
            direction = if (outgoing) MessageDirection.OUTGOING else MessageDirection.INCOMING,
            deliveryState = DeliveryState.DELIVERED,
            senderDisplayName = sender,
            attachments = attachments,
        )
    }

    private fun resolveBody(smack: SmackClientFacade, remoteJid: String, smackMsg: SmackMessage): String? {
        val helper = smack.omemoHelper
        if (helper?.hasOmemoPayload(smackMsg) == true) {
            // Fail-closed: never surface ciphertext/cleartext body for undecryptable OMEMO.
            return helper.tryDecrypt(remoteJid, smackMsg)
        }
        val carbonDir = CarbonExtension.from(smackMsg)?.direction
        if (carbonDir != null) {
            val forwarded = smackMsg.getExtension(Forwarded::class.java)
            val wrapped = forwarded?.forwardedStanza as? SmackMessage
            if (wrapped != null) {
                if (helper?.hasOmemoPayload(wrapped) == true) {
                    return helper.tryDecrypt(remoteJid, wrapped)
                }
                wrapped.body?.let { return it }
            }
        }
        return smackMsg.body
    }
}
