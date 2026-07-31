package ltechnologies.onionphone.securemessenger.protocol.xmpp

import android.content.Context
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jivesoftware.smack.AbstractXMPPConnection
import org.jivesoftware.smack.ConnectionConfiguration
import org.jivesoftware.smack.MessageListener
import org.jivesoftware.smack.ReconnectionManager
import org.jivesoftware.smack.SmackException
import org.jivesoftware.smack.chat2.Chat
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
import org.jivesoftware.smackx.chat_markers.ChatMarkersManager
import org.jivesoftware.smackx.chat_markers.element.ChatMarkersElements
import org.jivesoftware.smackx.chatstates.ChatState
import org.jivesoftware.smackx.chatstates.ChatStateListener
import org.jivesoftware.smackx.chatstates.ChatStateManager
import org.jivesoftware.smackx.delay.packet.DelayInformation
import org.jivesoftware.smackx.filetransfer.FileTransferManager
import org.jivesoftware.smackx.geoloc.GeoLocationManager
import org.jivesoftware.smackx.geoloc.packet.GeoLocation
import org.jivesoftware.smackx.httpfileupload.HttpFileUploadManager
import org.jivesoftware.smackx.httpfileupload.element.Slot
import org.jivesoftware.smackx.mam.MamManager
import org.jivesoftware.smackx.muc.MultiUserChat
import org.jivesoftware.smackx.muc.MultiUserChatManager
import org.jivesoftware.smackx.omemo.OmemoManager
import org.jivesoftware.smackx.ping.PingManager
import org.jivesoftware.smackx.receipts.DeliveryReceiptManager
import org.jivesoftware.smackx.receipts.DeliveryReceiptRequest
import org.jivesoftware.smackx.receipts.ReceiptReceivedListener
import org.jivesoftware.smackx.vcardtemp.VCardManager
import org.jivesoftware.smackx.vcardtemp.packet.VCard
import org.jxmpp.jid.EntityBareJid
import org.jxmpp.jid.impl.JidCreate
import org.jxmpp.jid.parts.Resourcepart
import ltechnologies.onionphone.securemessenger.core.model.ProxyConfig
import ltechnologies.onionphone.securemessenger.core.proxy.SocksEndpointResolver

/**
 * Smack SDK facade — RFC 6120 JID parsing, RFC 6121 roster, XEP-0313 MAM, XEP-0384 OMEMO,
 * XEP-0045 MUC, XEP-0363 HTTP File Upload, XEP-0085 chat states, XEP-0184 receipts,
 * XEP-0333 chat markers, XEP-0054 vCard, XEP-0080 geolocation.
 */
class SmackClientFacade(
    private val context: Context,
) {
    var connection: XMPPTCPConnection? = null
        private set

    private val joinedMucs = ConcurrentHashMap<String, MultiUserChat>()
    private val mucMessageListeners = ConcurrentHashMap<String, MessageListener>()

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

    val chatStateManager: ChatStateManager?
        get() = connection?.let { ChatStateManager.getInstance(it) }

    val deliveryReceiptManager: DeliveryReceiptManager?
        get() = connection?.let { DeliveryReceiptManager.getInstanceFor(it) }

    val chatMarkersManager: ChatMarkersManager?
        get() = connection?.let { ChatMarkersManager.getInstanceFor(it) }

    val vCardManager: VCardManager?
        get() = connection?.let { VCardManager.getInstanceFor(it) }

    val geoLocationManager: GeoLocationManager?
        get() = connection?.let { GeoLocationManager.getInstanceFor(it) }

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

        if (proxy.torRequired) {
            val socksHost = SocksEndpointResolver.resolveReachableHost(proxy.host, proxy.port)
            try {
                java.net.Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress(socksHost, proxy.port), 3_000)
                }
            } catch (e: Exception) {
                throw IllegalStateException(
                    "Tor activé : SOCKS $socksHost:${proxy.port} injoignable — démarrez OnionVPN ou désactivez Tor",
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
        }

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
            val receipts = DeliveryReceiptManager.getInstanceFor(conn)
            receipts.autoAddDeliveryReceiptRequests()
            receipts.setAutoReceiptMode(DeliveryReceiptManager.AutoReceiptMode.always)
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

    fun chatWith(remoteJid: String): Chat {
        val conn = connection ?: throw SmackException.NotConnectedException()
        return ChatManager.getInstanceFor(conn).chatWith(JidCreate.entityBareFrom(remoteJid))
    }

    /**
     * XEP-0085: [typing]=true → composing; false → paused (active after idle is left to peer).
     */
    fun setTyping(remoteJid: String, typing: Boolean) {
        val manager = chatStateManager ?: return
        val chat = chatWith(remoteJid)
        val state = if (typing) ChatState.composing else ChatState.paused
        manager.setCurrentState(state, chat)
    }

    fun addChatStateListener(listener: ChatStateListener) {
        chatStateManager?.addChatStateListener(listener)
    }

    fun addReceiptReceivedListener(listener: ReceiptReceivedListener) {
        deliveryReceiptManager?.addReceiptReceivedListener(listener)
    }

    fun addChatMarkerListener(
        listener: org.jivesoftware.smackx.chat_markers.ChatMarkersListener,
    ) {
        chatMarkersManager?.addIncomingChatMarkerMessageListener(listener)
    }

    /** XEP-0333 `<displayed/>` for [stanzaId]. */
    fun markDisplayed(remoteJid: String, stanzaId: String) {
        val conn = connection ?: throw SmackException.NotConnectedException()
        val message = conn.stanzaFactory.buildMessageStanza()
            .to(JidCreate.entityBareFrom(remoteJid))
            .addExtension(ChatMarkersElements.DisplayedExtension(stanzaId))
            .build()
        conn.sendStanza(message)
    }

    /**
     * Sends a chat body and returns the outgoing stanza id (for receipts / local message id).
     * Attaches XEP-0184 receipt request + XEP-0333 markable when [requestReceipts] is true.
     */
    fun sendChatMessage(
        remoteJid: String,
        body: String,
        requestReceipts: Boolean = true,
    ): String {
        val conn = connection ?: throw SmackException.NotConnectedException()
        val jid: EntityBareJid = JidCreate.entityBareFrom(remoteJid)
        val chat = ChatManager.getInstanceFor(conn).chatWith(jid)
        val helper = omemoHelper
        if (helper != null && helper.ready && helper.contactSupportsOmemo(remoteJid)) {
            // Fail-closed: never fall back to cleartext when the contact supports OMEMO.
            val encrypted = helper.sendEncrypted(remoteJid, body)
            if (requestReceipts) {
                attachReceiptExtensions(encrypted)
            }
            chat.send(encrypted)
            return encrypted.stanzaId.orEmpty()
        }
        val builder = conn.stanzaFactory.buildMessageStanza().setBody(body)
        if (requestReceipts) {
            DeliveryReceiptRequest.addTo(builder)
            builder.addExtension(ChatMarkersElements.MarkableExtension.INSTANCE)
        }
        val message = builder.build()
        chat.send(message)
        return message.stanzaId.orEmpty()
    }

    fun sendMucMessage(roomJid: String, body: String): String {
        val muc = joinedMucs[roomJid] ?: run {
            val conn = connection ?: throw SmackException.NotConnectedException()
            MultiUserChatManager.getInstanceFor(conn)
                .getMultiUserChat(JidCreate.entityBareFrom(roomJid))
        }
        val helper = omemoHelper
        if (helper != null && helper.ready && helper.multiUserChatSupportsOmemo(muc)) {
            // Fail-closed: never send cleartext when the room advertises OMEMO.
            val encrypted = helper.encryptMuc(muc, body)
            muc.sendMessage(encrypted)
            return encrypted.stanzaId.orEmpty()
        }
        val conn = connection ?: throw SmackException.NotConnectedException()
        val message = conn.stanzaFactory.buildMessageStanza().setBody(body).build()
        muc.sendMessage(message)
        return message.stanzaId.orEmpty()
    }

    fun sendGeoLocation(
        remoteJid: String,
        latitude: Double,
        longitude: Double,
        accuracy: Double = 0.0,
    ) {
        val manager = geoLocationManager ?: throw SmackException.NotConnectedException()
        val builder = GeoLocation.builder()
            .setLat(latitude)
            .setLon(longitude)
        if (accuracy > 0.0) {
            builder.setAccuracy(accuracy)
        }
        manager.sendGeoLocationToJid(builder.build(), JidCreate.from(remoteJid))
    }

    fun loadOwnVCard(): VCard {
        val manager = vCardManager ?: throw SmackException.NotConnectedException()
        return manager.loadVCard()
    }

    fun saveOwnVCard(displayName: String, bio: String?) {
        val manager = vCardManager ?: throw SmackException.NotConnectedException()
        val existing = runCatching { manager.loadVCard() }.getOrElse { VCard() }
        val trimmed = displayName.trim()
        if (trimmed.isNotEmpty()) {
            existing.nickName = trimmed
            val parts = trimmed.split(Regex("\\s+"), limit = 2)
            existing.firstName = parts.getOrNull(0).orEmpty()
            existing.lastName = parts.getOrNull(1).orEmpty()
        }
        if (bio != null) {
            existing.setField("DESC", bio)
        }
        myBareJid()?.let { existing.setJabberId(it) }
        manager.saveVCard(existing)
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
            mucMessageListeners.remove(roomJid)?.let { old ->
                runCatching { muc.removeMessageListener(old) }
            }
            val listener = MessageListener { message -> handler(message) }
            muc.addMessageListener(listener)
            mucMessageListeners[roomJid] = listener
        }
        joinedMucs[roomJid] = muc
    }

    fun isMucRoom(roomJid: String): Boolean =
        joinedMucs.containsKey(roomJid) || isLikelyMucJid(roomJid)

    /**
     * XEP-0363 upload. When [contentType] is set (e.g. `audio/ogg`, `image/gif`), the slot IQ
     * requests that MIME; PUT still uses Smack-compatible octet-stream + connection proxy.
     */
    suspend fun uploadFile(file: File, contentType: String? = null): URL = withContext(Dispatchers.IO) {
        val manager = httpFileUploadManager ?: throw SmackException.NotConnectedException()
        if (!manager.isUploadServiceDiscovered) {
            manager.discoverUploadService()
        }
        val mime = contentType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        if (mime == "application/octet-stream") {
            return@withContext manager.uploadFile(file)
        }
        val slot = manager.requestSlot(file.name, file.length(), mime)
        putHttpUploadSlot(slot, file)
        slot.getGetUrl()
    }

    private fun putHttpUploadSlot(slot: Slot, file: File) {
        val conn = connection ?: throw SmackException.NotConnectedException()
        val putUrl = slot.getPutUrl()
        val proxyInfo = (conn as? AbstractXMPPConnection)?.configuration?.proxyInfo
        val urlConnection = (
            if (proxyInfo != null) {
                putUrl.openConnection(proxyInfo.toJavaProxy())
            } else {
                putUrl.openConnection()
            }
            ) as HttpURLConnection
        urlConnection.requestMethod = "PUT"
        urlConnection.useCaches = false
        urlConnection.doOutput = true
        urlConnection.setFixedLengthStreamingMode(file.length())
        urlConnection.setRequestProperty("Content-Type", "application/octet-stream")
        for ((key, value) in slot.headers) {
            urlConnection.setRequestProperty(key, value)
        }
        try {
            FileInputStream(file).use { fis ->
                BufferedInputStream(fis).use { input ->
                    urlConnection.outputStream.use { output ->
                        input.copyTo(output)
                    }
                }
            }
            val status = urlConnection.responseCode
            if (status != HttpURLConnection.HTTP_OK &&
                status != HttpURLConnection.HTTP_CREATED &&
                status != HttpURLConnection.HTTP_NO_CONTENT
            ) {
                throw IOException(
                    "HTTP upload failed: $status ${urlConnection.responseMessage}",
                )
            }
        } finally {
            urlConnection.disconnect()
        }
    }

    fun disconnect() {
        mucMessageListeners.clear()
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

    private fun attachReceiptExtensions(message: SmackMessage) {
        if (!DeliveryReceiptManager.hasDeliveryReceiptRequest(message)) {
            DeliveryReceiptRequest.addTo(message)
        }
        if (ChatMarkersElements.MarkableExtension.from(message) == null) {
            message.addExtension(ChatMarkersElements.MarkableExtension.INSTANCE)
        }
    }

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

        fun formatContactVCard(
            firstName: String,
            lastName: String,
            phone: String?,
        ): String = buildString {
            append("BEGIN:VCARD\n")
            append("VERSION:3.0\n")
            append("N:").append(lastName).append(';').append(firstName).append(";;;\n")
            val fn = listOf(firstName, lastName).filter { it.isNotBlank() }.joinToString(" ").ifBlank { firstName }
            append("FN:").append(fn).append('\n')
            if (!phone.isNullOrBlank()) {
                append("TEL;TYPE=CELL:").append(phone.trim()).append('\n')
            }
            append("END:VCARD")
        }
    }
}
