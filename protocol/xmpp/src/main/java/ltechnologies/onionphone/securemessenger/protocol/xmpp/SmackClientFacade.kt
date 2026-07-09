package ltechnologies.onionphone.securemessenger.protocol.xmpp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jivesoftware.smack.ConnectionConfiguration
import org.jivesoftware.smack.ReconnectionManager
import org.jivesoftware.smack.SmackException
import org.jivesoftware.smack.chat2.ChatManager
import org.jivesoftware.smack.packet.Message as SmackMessage
import org.jivesoftware.smack.roster.Roster
import org.jivesoftware.smack.roster.RosterEntry
import org.jivesoftware.smack.tcp.XMPPTCPConnection
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration
import org.jivesoftware.smackx.carbons.CarbonManager
import org.jivesoftware.smackx.carbons.packet.CarbonExtension
import org.jivesoftware.smackx.delay.packet.DelayInformation
import org.jivesoftware.smackx.filetransfer.FileTransferManager
import org.jivesoftware.smackx.mam.MamManager
import org.jivesoftware.smackx.muc.MultiUserChatManager
import org.jivesoftware.smackx.ping.PingManager
import org.jivesoftware.smackx.omemo.OmemoManager
import org.jxmpp.jid.EntityBareJid
import org.jxmpp.jid.impl.JidCreate
import org.jxmpp.jid.parts.Resourcepart
import ltechnologies.onionphone.securemessenger.core.model.ProxyConfig
import ltechnologies.onionphone.securemessenger.core.proxy.SocksEndpointResolver

/**
 * Smack SDK facade — RFC 6120 JID parsing, RFC 6121 roster, XEP-0313 MAM, XEP-0384 OMEMO.
 */
class SmackClientFacade(
    private val context: Context,
) {
    var connection: XMPPTCPConnection? = null
        private set

    val chatManager: ChatManager?
        get() = connection?.let { ChatManager.getInstanceFor(it) }

    val roster: Roster?
        get() = connection?.let { Roster.getInstanceFor(it) }

    val mucManager: MultiUserChatManager?
        get() = connection?.let { MultiUserChatManager.getInstanceFor(it) }

    val pingManager: PingManager?
        get() = connection?.let { PingManager.getInstanceFor(it) }

    val reconnectionManager: ReconnectionManager?
        get() = connection?.let { ReconnectionManager.getInstanceFor(it) }

    val carbonManager: CarbonManager?
        get() = connection?.let { CarbonManager.getInstanceFor(it) }

    val mamManager: MamManager?
        get() = connection?.let { MamManager.getInstanceFor(it) }

    val fileTransferManager: FileTransferManager?
        get() = connection?.let { FileTransferManager.getInstanceFor(it) }

    val omemoManager: OmemoManager?
        get() = connection?.let { OmemoManager.getInstanceFor(it) }

    var omemoHelper: OmemoHelper? = null
        private set

    @Throws(SmackException::class, Exception::class)
    suspend fun connect(
        jid: String,
        password: String,
        server: String?,
        proxy: ProxyConfig,
    ): XMPPTCPConnection = withContext(Dispatchers.IO) {
        XmppInitializer.ensureInitialized(context)

        val bareJid = JidCreate.entityBareFrom(jid.trim())
        val serverHost = server?.takeIf { it.isNotBlank() } ?: bareJid.domain.toString()
        val builder = XMPPTCPConnectionConfiguration.builder()
            // RFC 6120: SASL auth uses localpart + domain, not full JID string.
            .setUsernameAndPassword(bareJid.localpart.toString(), password)
            .setXmppDomain(bareJid.domain.toString())
            .setHost(serverHost)
            .setPort(5222)
            .setResource("SecureMessenger")
            .setSecurityMode(ConnectionConfiguration.SecurityMode.required)
            .setCompressionEnabled(true)
            .setSendPresence(true)

        val socksHost = SocksEndpointResolver.resolveReachableHost(proxy.host, proxy.port)
        // Smack's SOCKS5 client always advertises both no-auth (0x00) and
        // username/password (0x02) methods. Tor's SocksPort commonly selects 0x02
        // — using the username/password purely as a stream-isolation token, not real
        // credentials — regardless of whether the caller supplied any. If we leave
        // these null, Smack has nothing to send once Tor picks 0x02 and the whole
        // handshake fails with "fail in SOCKS5 proxy" before ever reaching the
        // destination CONNECT request. Always supply a value (bare JID doubles as a
        // free per-account circuit isolation key) so the handshake can complete either way.
        val proxyInfo = org.jivesoftware.smack.proxy.ProxyInfo(
            org.jivesoftware.smack.proxy.ProxyInfo.ProxyType.SOCKS5,
            socksHost,
            proxy.port,
            proxy.username ?: bareJid.toString(),
            proxy.password ?: "x",
        )
        builder.setProxyInfo(proxyInfo)

        disconnect()

        val conn = XMPPTCPConnection(builder.build())
        conn.connect()
        conn.login()

        PingManager.getInstanceFor(conn).pingInterval = 60
        ReconnectionManager.getInstanceFor(conn).enableAutomaticReconnection()

        try {
            CarbonManager.getInstanceFor(conn).enableCarbons()
        } catch (_: Exception) {
        }

        try {
            MamManager.getInstanceFor(conn).enableMamForAllMessages()
        } catch (_: Exception) {
        }

        try {
            val omemo = OmemoManager.getInstanceFor(conn)
            omemoHelper = OmemoHelper(omemo, conn)
            omemoHelper?.initializeAsync()
        } catch (_: Exception) {
            omemoHelper = null
        }

        connection = conn
        conn
    }

    fun rosterEntries(): List<RosterEntry> = roster?.entries?.toList().orEmpty()

    fun sendChatMessage(remoteJid: String, body: String) {
        val conn = connection ?: throw SmackException.NotConnectedException()
        val jid: EntityBareJid = JidCreate.entityBareFrom(remoteJid)
        val chat = ChatManager.getInstanceFor(conn).chatWith(jid)
        val helper = omemoHelper
        val msg = if (helper != null && helper.ready && helper.contactSupportsOmemo(remoteJid)) {
            helper.sendEncrypted(remoteJid, body)
        } else {
            conn.stanzaFactory.buildMessageStanza().setBody(body).build()
        }
        chat.send(msg)
    }

    fun joinMuc(roomJid: String, nickname: String) {
        val conn = connection ?: throw SmackException.NotConnectedException()
        val muc = MultiUserChatManager.getInstanceFor(conn)
            .getMultiUserChat(JidCreate.entityBareFrom(roomJid))
        if (!muc.isJoined) {
            muc.create(Resourcepart.from(nickname))
        }
    }

    fun disconnect() {
        try {
            connection?.disconnect()
        } catch (_: Exception) {
        }
        connection = null
        omemoHelper = null
    }

    fun isConnected(): Boolean = connection?.isConnected == true && connection?.isAuthenticated == true

    fun myBareJid(): String? = connection?.user?.asBareJid()?.toString()

    companion object {
        fun extractDelayTimestamp(message: SmackMessage): Long? =
            message.getExtension(DelayInformation::class.java)?.stamp?.time

        fun rosterJidString(entry: RosterEntry): String = entry.jid.toString()

        /** XEP-0280 Message Carbons — detect sent copy vs peer message. */
        fun isCarbonSent(message: SmackMessage): Boolean {
            val carbon = CarbonExtension.from(message) ?: return false
            return carbon.direction == CarbonExtension.Direction.sent
        }
    }
}
