package ltechnologies.onionphone.securemessenger.protocol.xmpp

import org.jivesoftware.smack.packet.Message as SmackMessage
import org.jivesoftware.smackx.carbons.packet.CarbonExtension
import org.jivesoftware.smackx.forward.packet.Forwarded
import org.jivesoftware.smackx.mam.MamManager
import org.jxmpp.jid.impl.JidCreate
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
        val queryArgs = MamManager.MamQueryArgs.builder()
            .limitResultsToJid(with)
            .setResultPageSizeTo(maxResults)
            .build()
        val result = mam.queryArchive(queryArgs)
        val myJid = smack.myBareJid()
        val messages = result.messages.mapNotNull { smackMsg ->
            val body = resolveBody(smack, remoteJid, smackMsg) ?: return@mapNotNull null
            val convId = XmppProtocol.conversationIdFor(accountId, remoteJid)
            val ts = SmackClientFacade.extractDelayTimestamp(smackMsg) ?: System.currentTimeMillis()
            val outgoing = SmackClientFacade.isCarbonSent(smackMsg) ||
                smackMsg.from?.asBareJid()?.toString() == myJid
            Message(
                id = "${convId}_mam_${smackMsg.stanzaId ?: ts}",
                conversationId = convId,
                protocol = ProtocolId.XMPP,
                body = body,
                timestamp = ts,
                direction = if (outgoing) MessageDirection.OUTGOING else MessageDirection.INCOMING,
                deliveryState = DeliveryState.DELIVERED,
                senderDisplayName = if (outgoing) myJid else remoteJid,
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
