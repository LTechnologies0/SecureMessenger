package ltechnologies.onionphone.securemessenger.protocol.xmpp

import android.content.Context
import java.io.File
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jivesoftware.smack.ConnectionConfiguration
import org.jivesoftware.smack.MessageListener
import org.jivesoftware.smack.ReconnectionManager
import org.jivesoftware.smack.SmackException
import org.jivesoftware.smack.chat2.ChatManager
import org.jivesoftware.smack.packet.Message as SmackMessage
import org.jivesoftware.smack.roster.Roster
import org.jivesoftware.smack.roster.RosterEntry
import org.jivesoftware.smack.tcp.XMPPTCPConnection
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration
import org.jivesoftware.smackx.bookmarks.BookmarkManager
import org.jivesoftware.smackx.bookmarks.BookmarkedConference
import org.jivesoftware.smackx.carbons.CarbonManager
import org.jivesoftware.smackx.carbons.packet.CarbonExtension
import org.jivesoftware.smackx.delay.packet.DelayInformation
import org.jivesoftware.smackx.filetransfer.FileTransferManager
import org.jivesoftware.smackx.httpfileupload.HttpFileUploadManager
import org.jivesoftware.smackx.mam.MamManager
import org.jivesoftware.smackx.muc.MultiUserChat
import org.jivesoftware.smackx.muc.MultiUserChatManager
import org.jivesoftware.smackx.omemo.OmemoManager
import org.jivesoftware.smackx.ping.PingManager
import org.jxmpp.jid.EntityBareJid
import org.jxmpp.jid.impl.JidCreate
import org.jxmpp.jid.parts.Resourcepart
import ltechnologies.onionphone.securemessenger.core.model.ProxyConfig
import ltechnologies.onionphone.securemessenger.core.proxy.SocksEndpointResolver
import timber.log.Timber

/**
 * Smack SDK facade — RFC 6120 JID parsing, RFC 6121 roster, XEP-0313 MAM, XEP-0384 OMEMO,
 * XEP-0045 MUC, XEP-0363 HTTP File Upload.
 */
class SmackClientFacade(
    private val context: Context,
) {
    var connection: XMPPTCPConnection? = null
        private set

    private val joinedMucs = ConcurrentHashMap<String, MultiUserChat>()

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

    val httpFileUploadManager: HttpFileUploadManager?
        get() = connection?.let { HttpFileUploadManager.getInstanceFor(it) }

    val bookmarkManager: BookmarkManager?
        get() = connection?.let { BookmarkManager.getBookmarkManager(it) }

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
        try {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(socksHost, proxy.port), 3_000)
            }
        } catch (e: Exception) {
            throw IllegalStateException(
                "Tor requis : SOCKS $socksHost:${proxy.port} injoignable — démarrez Orbot ou InviZible",
                e,
            )
        }
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
            HttpFileUploadManager.getInstanceFor(conn).discoverUploadService()
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

    fun bookmarkedConferences(): List<BookmarkedConference> = try {
        val manager = bookmarkManager ?: return emptyList()
        if (!manager.isSupported) emptyList() else manager.getBookmarkedConferences()
    } catch (_: Exception) {
        emptyList()
    }

    fun sendChatMessage(remoteJid: String, body: String) {
        val conn = connection ?: throw SmackException.NotConnectedException()
        val jid: EntityBareJid = JidCreate.entityBareFrom(remoteJid)
        val chat = ChatManager.getInstanceFor(conn).chatWith(jid)
        val helper = omemoHelper
        if (helper != null && helper.ready && helper.contactSupportsOmemo(remoteJid)) {
            // Fail-closed: never fall back to cleartext when the contact supports OMEMO.
            chat.send(helper.sendEncrypted(remoteJid, body))
            return
        }
        chat.send(conn.stanzaFactory.buildMessageStanza().setBody(body).build())
    }

    fun joinMuc(
        roomJid: String,
        nickname: String,
        onMessage: ((SmackMessage) -> Unit)? = null,
    ) {
        val conn = connection ?: throw SmackException.NotConnectedException()
        val muc = MultiUserChatManager.getInstanceFor(conn)
            .getMultiUserChat(JidCreate.entityBareFrom(roomJid))
        if (!muc.isJoined) {
            muc.join(Resourcepart.from(nickname))
        }
        onMessage?.let { handler ->
            val listener = MessageListener { message -> handler(message) }
            muc.addMessageListener(listener)
        }
        joinedMucs[roomJid] = muc
    }

    fun isMucRoom(roomJid: String): Boolean =
        joinedMucs.containsKey(roomJid) || isLikelyMucJid(roomJid)

    fun sendMucMessage(roomJid: String, body: String) {
        val muc = joinedMucs[roomJid] ?: run {
            val conn = connection ?: throw SmackException.NotConnectedException()
            MultiUserChatManager.getInstanceFor(conn)
                .getMultiUserChat(JidCreate.entityBareFrom(roomJid))
        }
        val helper = omemoHelper
        if (helper != null && helper.ready && helper.multiUserChatSupportsOmemo(muc)) {
            // Fail-closed: never send cleartext when the room advertises OMEMO.
            muc.sendMessage(helper.encryptMuc(muc, body))
            return
        }
        muc.sendMessage(body)
    }

    suspend fun uploadFile(file: File): URL = withContext(Dispatchers.IO) {
        val manager = httpFileUploadManager ?: throw SmackException.NotConnectedException()
        if (!manager.isUploadServiceDiscovered) {
            manager.discoverUploadService()
        }
        manager.uploadFile(file)
    }

    fun disconnect() {
        joinedMucs.clear()
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

        /** XEP-0363 / XEP-0454: uploaded file URLs (https or aesgcm) as message body. */
        fun extractHttpUploadUrl(body: String): String? {
            val trimmed = body.trim()
            return when {
                trimmed.startsWith("https://") || trimmed.startsWith("http://") -> trimmed
                trimmed.startsWith("aesgcm://") -> trimmed
                else -> {
                    // Prefer first URL-looking token in multi-line bodies.
                    trimmed.lineSequence()
                        .map { it.trim() }
                        .firstOrNull {
                            it.startsWith("https://") ||
                                it.startsWith("http://") ||
                                it.startsWith("aesgcm://")
                        }
                }
            }
        }

        /** Best-effort XEP-0066 OOB URL from raw stanza XML when body is not the URL. */
        fun extractOobUrl(message: SmackMessage): String? {
            val xml = message.toXML().toString()
            val match = Regex(
                """<url[^>]*>\s*(https?://[^<\s]+|aesgcm://[^<\s]+)\s*</url>""",
                RegexOption.IGNORE_CASE,
            ).find(xml)
            return match?.groupValues?.getOrNull(1)?.trim()
        }

        fun isLikelyMucJid(jid: String): Boolean {
            val lower = jid.lowercase()
            return lower.contains("@conference.") || lower.contains("@muc.")
        }
    }
}
