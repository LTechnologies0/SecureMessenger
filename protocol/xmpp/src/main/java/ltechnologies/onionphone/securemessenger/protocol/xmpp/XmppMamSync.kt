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

/** XEP-0313 MAM archive sync with XEP-0384 OMEMO decryption and XEP-0280 carbon direction. */
object XmppMamSync {
    suspend fun syncHistory(
        smack: SmackClientFacade,
        accountId: String,
        repository: MessengerRepository,
        remoteJid: String,
        maxResults: Int = 50,
    ) {
        val mam = smack.mamManager ?: return
        if (!mam.isSupported) return
        val with = JidCreate.entityBareFrom(remoteJid)
        val queryArgsBuilder = MamManager.MamQueryArgs.builder()
            .setResultPageSizeTo(maxResults)
        // Direct chats: filter by peer. MUC rooms: archive is often queried against the room JID.
        queryArgsBuilder.limitResultsToJid(with)
        val result = mam.queryArchive(queryArgsBuilder.build())
        val myJid = smack.myBareJid()
        val isMuc = SmackClientFacade.isLikelyMucJid(remoteJid) || smack.isMucRoom(remoteJid)
        val messages = result.messages.mapNotNull { smackMsg ->
            val body = resolveBody(smack, remoteJid, smackMsg) ?: return@mapNotNull null
            val convId = XmppProtocol.conversationIdFor(accountId, remoteJid)
            val ts = SmackClientFacade.extractDelayTimestamp(smackMsg) ?: System.currentTimeMillis()
            val fromBare = smackMsg.from?.asBareJid()?.toString()
            val outgoing = SmackClientFacade.isCarbonSent(smackMsg) || fromBare == myJid
            val uploadUrl = SmackClientFacade.extractHttpUploadUrl(body)
                ?: SmackClientFacade.extractOobUrl(smackMsg)
            val attachments = uploadUrl?.let { url ->
                listOf(
                    Attachment(
                        id = "${convId}_mam_${smackMsg.stanzaId ?: ts}_file",
                        mimeType = "application/octet-stream",
                        fileName = url.substringAfterLast('/').takeIf { it.isNotBlank() },
                        remoteRef = url,
                        state = AttachmentState.READY,
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
            Message(
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
        if (messages.isNotEmpty()) {
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
    }

    private fun resolveBody(smack: SmackClientFacade, remoteJid: String, smackMsg: SmackMessage): String? {
        smack.omemoHelper?.tryDecrypt(remoteJid, smackMsg)?.let { return it }
        val carbonDir = CarbonExtension.from(smackMsg)?.direction
        if (carbonDir != null) {
            val forwarded = smackMsg.getExtension(Forwarded::class.java)
            val wrapped = forwarded?.forwardedStanza as? SmackMessage
            if (wrapped != null) {
                smack.omemoHelper?.tryDecrypt(remoteJid, wrapped)?.let { return it }
                wrapped.body?.let { return it }
            }
        }
        return smackMsg.body
    }
}
