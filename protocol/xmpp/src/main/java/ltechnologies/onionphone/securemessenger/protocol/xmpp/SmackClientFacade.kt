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
import timber.log.Timber
import org.jivesoftware.smack.AbstractXMPPConnection
import org.jivesoftware.smack.ConnectionConfiguration
import org.jivesoftware.smack.MessageListener
import org.jivesoftware.smack.ReconnectionListener
import org.jivesoftware.smack.ReconnectionManager
import org.jivesoftware.smack.SmackException
import org.jivesoftware.smack.chat2.Chat
import org.jivesoftware.smack.chat2.ChatManager
import org.jivesoftware.smack.packet.Message as SmackMessage
import org.jivesoftware.smack.packet.Presence
import org.jivesoftware.smack.roster.Roster
import org.jivesoftware.smack.roster.RosterEntry
import org.jivesoftware.smack.tcp.XMPPTCPConnection
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration
import org.jivesoftware.smackx.blocking.BlockingCommandManager
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
import org.jivesoftware.smackx.disco.ServiceDiscoveryManager
import org.jivesoftware.smackx.filetransfer.FileTransferManager
import org.jivesoftware.smackx.geoloc.GeoLocationManager
import org.jivesoftware.smackx.geoloc.packet.GeoLocation
import org.jivesoftware.smackx.httpfileupload.HttpFileUploadManager
import org.jivesoftware.smackx.httpfileupload.element.Slot
import org.jivesoftware.smackx.iqlast.LastActivityManager
import org.jivesoftware.smackx.mam.MamManager
import org.jivesoftware.smackx.message_correct.element.MessageCorrectExtension
import org.jivesoftware.smackx.message_fastening.element.FasteningElement
import org.jivesoftware.smackx.message_retraction.MessageRetractionManager
import org.jivesoftware.smackx.message_retraction.element.RetractElement
import org.jivesoftware.smackx.muc.MultiUserChat
import org.jivesoftware.smackx.muc.MultiUserChatManager
import org.jivesoftware.smackx.omemo.OmemoManager
import org.jivesoftware.smackx.omemo.listener.OmemoMessageListener
import org.jivesoftware.smackx.ping.PingManager
import org.jivesoftware.smackx.receipts.DeliveryReceiptManager
import org.jivesoftware.smackx.receipts.DeliveryReceiptRequest
import org.jivesoftware.smackx.receipts.ReceiptReceivedListener
import org.jivesoftware.smackx.sid.element.OriginIdElement
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
 * XEP-0333 chat markers, XEP-0054 vCard, XEP-0080 geolocation, XEP-0308 correction,
 * XEP-0444 reactions, XEP-0424 retraction, XEP-0191 blocking, XEP-0048 bookmarks.
 */
class SmackClientFacade(
    private val context: Context,
) {
    var connection: XMPPTCPConnection? = null
        private set

    fun filesDir(): java.io.File = context.filesDir

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

    val blockingManager: BlockingCommandManager?
        get() = connection?.let { BlockingCommandManager.getInstanceFor(it) }

    val lastActivityManager: LastActivityManager?
        get() = connection?.let { LastActivityManager.getInstanceFor(it) }

    val messageRetractionManager: MessageRetractionManager?
        get() = connection?.let { MessageRetractionManager.getInstanceFor(it) }

    var omemoHelper: OmemoHelper? = null
        private set

    private var omemoLiveListener: OmemoMessageListener? = null

    /**
     * Delivers decrypted OMEMO chat bodies (including XEP-0280 carbons). Plaintext stanzas use
     * [ChatManager] listeners instead.
     */
    fun setOmemoLiveHandler(
        handler: (remoteJid: String, body: String, stanza: SmackMessage, outgoing: Boolean) -> Unit,
    ) {
        val omemo = omemoManager ?: return
        omemoLiveListener?.let { omemo.removeOmemoMessageListener(it) }
        val listener = object : OmemoMessageListener {
            override fun onOmemoMessageReceived(
                stanza: org.jivesoftware.smack.packet.Stanza,
                received: org.jivesoftware.smackx.omemo.OmemoMessage.Received,
            ) {
                val body = received.body ?: return
                val remote = stanza.from?.asBareJid()?.toString() ?: return
                handler(remote, body, stanza as SmackMessage, false)
            }

            override fun onOmemoCarbonCopyReceived(
                direction: CarbonExtension.Direction,
                carbonCopy: SmackMessage,
                wrappingMessage: SmackMessage,
                received: org.jivesoftware.smackx.omemo.OmemoMessage.Received,
            ) {
                val body = received.body ?: return
                val remote = carbonCopy.to?.asBareJid()?.toString()
                    ?: carbonCopy.from?.asBareJid()?.toString()
                    ?: return
                val outgoing = direction == CarbonExtension.Direction.sent
                handler(remote, body, carbonCopy, outgoing)
            }
        }
        omemo.addOmemoMessageListener(listener)
        omemoLiveListener = listener
    }

    @Throws(SmackException::class, Exception::class)
    suspend fun connect(
        accountId: String,
        jid: String,
        password: String,
        server: String?,
        proxy: ProxyConfig,
        omemoLiveHandler: ((
            remoteJid: String,
            body: String,
            stanza: SmackMessage,
            outgoing: Boolean,
        ) -> Unit)? = null,
    ): XMPPTCPConnection = withContext(Dispatchers.IO) {
        XmppInitializer.ensureInitialized(context)
        val omemoServiceReady = XmppInitializer.ensureOmemoSignalStore(context, accountId)

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
                "Tor requis : SOCKS $socksHost:${proxy.port} injoignable — démarrez OnionVPN",
                e,
            )
        }
        builder.setSocketFactory(SocksUnresolvedSocketFactory(socksHost, proxy.port))
        org.jivesoftware.smack.util.DNSUtil.setDNSResolver(DummyTorDnsResolver())
        builder.setProxyInfo(
            smackSocksProxyInfo(socksHost, proxy.port, proxy.username, proxy.password),
        )

        disconnect()

        val conn = XMPPTCPConnection(builder.build())
        conn.connect()
        connection = conn
        if (omemoServiceReady && omemoLiveHandler != null) {
            setOmemoLiveHandler(omemoLiveHandler)
        }
        conn.login()

        PingManager.getInstanceFor(conn).pingInterval = 60
        ReconnectionManager.getInstanceFor(conn).enableAutomaticReconnection()

        XmppReactionExtension.ensureProviderRegistered()
        try {
            val disco = ServiceDiscoveryManager.getInstanceFor(conn)
            disco.addFeature(MessageCorrectExtension.NAMESPACE)
            disco.addFeature(XmppReactionExtension.NAMESPACE)
            disco.addFeature("urn:xmpp:message-retract:0")
            MessageRetractionManager.getInstanceFor(conn).announceSupport()
        } catch (_: Exception) {
        }

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

        if (omemoServiceReady) {
            try {
                val omemo = OmemoManager.getInstanceFor(conn)
                omemoHelper = OmemoHelper(omemo, conn)
                val published = omemoHelper?.initialize() == true
                if (!published) {
                    Timber.w("OMEMO device list not published — encrypt blocked until retry")
                }
            } catch (e: Exception) {
                Timber.w(e, "OMEMO init failed — helper discarded")
                omemoHelper = null
            }
        } else {
            Timber.w("OMEMO Signal service setup failed — E2EE disabled for this session")
            omemoHelper = null
        }

        conn
    }

    fun addReconnectionFailureListener(onFailure: (Exception) -> Unit) {
        val conn = connection ?: return
        ReconnectionManager.getInstanceFor(conn).addReconnectionListener(
            object : ReconnectionListener {
                override fun reconnectingIn(seconds: Int) = Unit

                override fun reconnectionFailed(e: Exception) {
                    onFailure(e)
                }
            },
        )
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
        when (
            OmemoHelper.decideSend(
                helperPresent = helper != null,
                helperReady = helper?.ready == true,
                peerSupports = helper?.contactSupportsOmemo(remoteJid),
            )
        ) {
            OmemoSendDecision.ENCRYPT -> {
                val encrypted = helper!!.sendEncrypted(remoteJid, body)
                if (requestReceipts) {
                    attachReceiptExtensions(encrypted)
                }
                attachOriginId(encrypted)
                chat.send(encrypted)
                return encrypted.stanzaId.orEmpty()
            }
            OmemoSendDecision.BLOCK ->
                throw IllegalStateException(
                    "OMEMO indisponible ou identité du contact inconnue — message non envoyé en clair",
                )
            OmemoSendDecision.PLAINTEXT -> Unit
        }
        val builder = conn.stanzaFactory.buildMessageStanza().setBody(body)
        if (requestReceipts) {
            DeliveryReceiptRequest.addTo(builder)
            builder.addExtension(ChatMarkersElements.MarkableExtension.INSTANCE)
        }
        OriginIdElement.addTo(builder)
        val message = builder.build()
        chat.send(message)
        return message.stanzaId.orEmpty()
    }

    /**
     * XEP-0308 Last Message Correction. Encrypts when OMEMO is required for the peer.
     * @return stanza id of the correction message
     */
    fun sendMessageCorrection(remoteJid: String, originalStanzaId: String, newBody: String): String {
        val conn = connection ?: throw SmackException.NotConnectedException()
        require(originalStanzaId.isNotBlank()) { "originalStanzaId blank" }
        val jid: EntityBareJid = JidCreate.entityBareFrom(remoteJid)
        val chat = ChatManager.getInstanceFor(conn).chatWith(jid)
        val correction = MessageCorrectExtension(originalStanzaId)
        val helper = omemoHelper
        when (
            OmemoHelper.decideSend(
                helperPresent = helper != null,
                helperReady = helper?.ready == true,
                peerSupports = helper?.contactSupportsOmemo(remoteJid),
            )
        ) {
            OmemoSendDecision.ENCRYPT -> {
                val encrypted = helper!!.sendEncrypted(remoteJid, newBody)
                encrypted.addExtension(correction)
                attachOriginId(encrypted)
                chat.send(encrypted)
                return encrypted.stanzaId.orEmpty()
            }
            OmemoSendDecision.BLOCK ->
                throw IllegalStateException("OMEMO indisponible — correction refusée en clair")
            OmemoSendDecision.PLAINTEXT -> Unit
        }
        val builder = conn.stanzaFactory.buildMessageStanza()
            .setBody(newBody)
            .addExtension(correction)
        OriginIdElement.addTo(builder)
        val message = builder.build()
        chat.send(message)
        return message.stanzaId.orEmpty()
    }

    /**
     * XEP-0444 reactions. Sent as a lightweight chat stanza (empty body) even on OMEMO chats —
     * reactions are metadata, not message content (matches Conversations interoperability).
     */
    fun sendReaction(remoteJid: String, targetStanzaId: String, emoji: String): String {
        val conn = connection ?: throw SmackException.NotConnectedException()
        require(targetStanzaId.isNotBlank()) { "targetStanzaId blank" }
        val trimmed = emoji.trim()
        require(trimmed.isNotEmpty()) { "emoji blank" }
        XmppReactionExtension.ensureProviderRegistered()
        val builder = conn.stanzaFactory.buildMessageStanza()
            .to(JidCreate.entityBareFrom(remoteJid))
            .ofType(SmackMessage.Type.chat)
            .addExtension(XmppReactionExtension(targetStanzaId, listOf(trimmed)))
        OriginIdElement.addTo(builder)
        val message = builder.build()
        conn.sendStanza(message)
        return message.stanzaId.orEmpty()
    }

    /** XEP-0424 Message Retraction addressed to [remoteJid] (Smack's manager omits `to`). */
    fun retractMessage(remoteJid: String, originId: String) {
        val conn = connection ?: throw SmackException.NotConnectedException()
        require(originId.isNotBlank()) { "originId blank" }
        val builder = conn.stanzaFactory.buildMessageStanza()
            .to(JidCreate.entityBareFrom(remoteJid))
            .ofType(SmackMessage.Type.chat)
        MessageRetractionManager.addRetractionElementToMessage(OriginIdElement(originId), builder)
        conn.sendStanza(builder.build())
    }

    fun blockJids(jids: List<String>) {
        val manager = blockingManager ?: throw SmackException.NotConnectedException()
        if (!manager.isSupportedByServer) error("XEP-0191 non supporté par le serveur")
        manager.blockContacts(jids.map { JidCreate.from(it) })
    }

    fun unblockJids(jids: List<String>) {
        val manager = blockingManager ?: throw SmackException.NotConnectedException()
        if (!manager.isSupportedByServer) error("XEP-0191 non supporté par le serveur")
        manager.unblockContacts(jids.map { JidCreate.from(it) })
    }

    fun blockList(): List<String> {
        val manager = blockingManager ?: return emptyList()
        if (!manager.isSupportedByServer) return emptyList()
        return manager.blockList.map { it.toString() }
    }

    fun addRosterContact(jid: String, name: String?) {
        val r = roster ?: throw SmackException.NotConnectedException()
        r.createItemAndRequestSubscription(
            JidCreate.bareFrom(jid),
            name?.takeIf { it.isNotBlank() },
            emptyArray(),
        )
    }

    fun removeRosterContact(jid: String) {
        val r = roster ?: throw SmackException.NotConnectedException()
        val entry = r.getEntry(JidCreate.bareFrom(jid)) ?: return
        r.removeEntry(entry)
    }

    fun peerPresence(jid: String): Presence? =
        roster?.getPresence(JidCreate.bareFrom(jid))

    fun queryLastActivitySeconds(jid: String): Long? {
        val manager = lastActivityManager ?: return null
        return runCatching {
            manager.getLastActivity(JidCreate.from(jid)).lastActivity
        }.getOrNull()
    }

    fun addBookmarkedConference(
        roomJid: String,
        name: String?,
        nickname: String,
        autoJoin: Boolean = true,
        password: String? = null,
    ) {
        val manager = bookmarkManager ?: throw SmackException.NotConnectedException()
        manager.addBookmarkedConference(
            name?.takeIf { it.isNotBlank() } ?: roomJid,
            JidCreate.entityBareFrom(roomJid),
            autoJoin,
            Resourcepart.from(nickname),
            password,
        )
    }

    fun removeBookmarkedConference(roomJid: String) {
        val manager = bookmarkManager ?: throw SmackException.NotConnectedException()
        manager.removeBookmarkedConference(JidCreate.entityBareFrom(roomJid))
    }

    fun loadPeerVCard(jid: String): VCard {
        val manager = vCardManager ?: throw SmackException.NotConnectedException()
        return manager.loadVCard(JidCreate.entityBareFrom(jid))
    }

    fun saveOwnAvatar(jpegOrPngBytes: ByteArray, mimeType: String = "image/jpeg") {
        val manager = vCardManager ?: throw SmackException.NotConnectedException()
        val existing = runCatching { manager.loadVCard() }.getOrElse { VCard() }
        existing.setAvatar(jpegOrPngBytes, mimeType)
        myBareJid()?.let { existing.setJabberId(it) }
        manager.saveVCard(existing)
    }

    fun loadOwnAvatarBytes(): ByteArray? =
        runCatching { loadOwnVCard().avatar }.getOrNull()

    fun loadPeerAvatarBytes(jid: String): ByteArray? =
        runCatching { loadPeerVCard(jid).avatar }.getOrNull()

    fun leaveMuc(roomJid: String) {
        val muc = joinedMucs.remove(roomJid) ?: run {
            val conn = connection ?: throw SmackException.NotConnectedException()
            MultiUserChatManager.getInstanceFor(conn)
                .getMultiUserChat(JidCreate.entityBareFrom(roomJid))
        }
        mucMessageListeners.remove(roomJid)?.let { listener ->
            runCatching { muc.removeMessageListener(listener) }
        }
        if (muc.isJoined) {
            muc.leave()
        }
    }

    fun changeMucSubject(roomJid: String, subject: String) {
        val muc = requireJoinedMuc(roomJid)
        muc.changeSubject(subject)
    }

    fun inviteToMuc(roomJid: String, inviteeJid: String, reason: String? = null) {
        val muc = requireJoinedMuc(roomJid)
        muc.invite(JidCreate.entityBareFrom(inviteeJid), reason.orEmpty())
    }

    fun mucOccupantJids(roomJid: String): List<String> {
        val muc = joinedMucs[roomJid] ?: return emptyList()
        return muc.occupants.map { it.toString() }
    }

    fun createOrJoinMuc(roomJid: String, nickname: String) {
        val conn = connection ?: throw SmackException.NotConnectedException()
        val muc = MultiUserChatManager.getInstanceFor(conn)
            .getMultiUserChat(JidCreate.entityBareFrom(roomJid))
        muc.createOrJoin(Resourcepart.from(nickname))
        joinedMucs[roomJid] = muc
    }

    fun kickMucParticipant(roomJid: String, nick: String, reason: String? = null) {
        requireJoinedMuc(roomJid).kickParticipant(Resourcepart.from(nick), reason.orEmpty())
    }

    fun banMucUser(roomJid: String, bareJid: String, reason: String? = null) {
        requireJoinedMuc(roomJid).banUser(JidCreate.bareFrom(bareJid), reason.orEmpty())
    }

    private fun requireJoinedMuc(roomJid: String): MultiUserChat {
        joinedMucs[roomJid]?.let { return it }
        val conn = connection ?: throw SmackException.NotConnectedException()
        val muc = MultiUserChatManager.getInstanceFor(conn)
            .getMultiUserChat(JidCreate.entityBareFrom(roomJid))
        if (!muc.isJoined) error("MUC non rejoint: $roomJid")
        joinedMucs[roomJid] = muc
        return muc
    }

    fun sendMucMessage(roomJid: String, body: String): String {
        val muc = joinedMucs[roomJid] ?: run {
            val conn = connection ?: throw SmackException.NotConnectedException()
            MultiUserChatManager.getInstanceFor(conn)
                .getMultiUserChat(JidCreate.entityBareFrom(roomJid))
        }
        val helper = omemoHelper
        when (
            OmemoHelper.decideSend(
                helperPresent = helper != null,
                helperReady = helper?.ready == true,
                peerSupports = helper?.multiUserChatSupportsOmemo(muc),
            )
        ) {
            OmemoSendDecision.ENCRYPT -> {
                val encrypted = helper!!.encryptMuc(muc, body)
                attachOriginId(encrypted)
                muc.sendMessage(encrypted.asBuilder())
                return encrypted.stanzaId.orEmpty()
            }
            OmemoSendDecision.BLOCK ->
                throw IllegalStateException(
                    "OMEMO du salon inconnu — message non envoyé en clair",
                )
            OmemoSendDecision.PLAINTEXT -> Unit
        }
        val conn = connection ?: throw SmackException.NotConnectedException()
        val builder = conn.stanzaFactory.buildMessageStanza().setBody(body)
        OriginIdElement.addTo(builder)
        val message = builder.build()
        muc.sendMessage(message.asBuilder())
        return message.stanzaId.orEmpty()
    }

    fun sendGeoLocation(
        remoteJid: String,
        latitude: Double,
        longitude: Double,
        accuracy: Double = 0.0,
    ) {
        val helper = omemoHelper
        val decision = OmemoHelper.decideSend(
            helperPresent = helper != null,
            helperReady = helper?.ready == true,
            peerSupports = helper?.contactSupportsOmemo(remoteJid),
        )
        if (decision != OmemoSendDecision.PLAINTEXT) {
            val body = buildString {
                append("geo:$latitude,$longitude")
                if (accuracy > 0.0) append(";u=$accuracy")
            }
            sendChatMessage(remoteJid, body, requestReceipts = false)
            return
        }
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
        val slot = manager.requestSlot(file.name, file.length(), mime)
        putHttpUploadSlot(slot, file)
        slot.getGetUrl()
    }

    private fun putHttpUploadSlot(slot: Slot, file: File) {
        val conn = connection ?: throw SmackException.NotConnectedException()
        val putUrl = slot.getPutUrl()
        val proxyInfo = (conn as? AbstractXMPPConnection)?.configuration?.proxyInfo
            ?: throw IOException("HTTP upload refused: session SOCKS proxy missing (Tor required)")
        val urlConnection = openTorHttpConnection(putUrl, proxyInfo) as HttpURLConnection
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
        omemoLiveListener?.let { listener ->
            runCatching { omemoManager?.removeOmemoMessageListener(listener) }
        }
        omemoLiveListener = null
        try {
            connection?.disconnect()
        } catch (_: Exception) {
        }
        connection = null
        omemoHelper = null
    }

    /**
     * Downloads an XEP-0363 / XEP-0454 URL through this connection's proxy, decrypting aesgcm
     * payloads when a fragment key is present.
     */
    fun downloadHttpUploadUrl(url: String, destination: File): Result<File> = runCatching {
        val parsed = parseUploadFetch(url)
        val conn = connection
            ?: throw SmackException.NotConnectedException()
        val proxyInfo = (conn as? AbstractXMPPConnection)?.configuration?.proxyInfo
            ?: throw IOException("HTTP download refused: session SOCKS proxy missing (Tor required)")
        val urlConnection = openTorHttpConnection(parsed.fetchUrl, proxyInfo) as HttpURLConnection
        urlConnection.requestMethod = "GET"
        urlConnection.useCaches = false
        urlConnection.connectTimeout = 30_000
        urlConnection.readTimeout = 60_000
        try {
            val status = urlConnection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP download failed: $status ${urlConnection.responseMessage}")
            }
            val declared = urlConnection.contentLengthLong
            if (declared > MAX_AUTO_FETCH_BYTES) {
                throw IOException("HTTP download exceeds $MAX_AUTO_FETCH_BYTES bytes")
            }
            val cipherBytes = urlConnection.inputStream.use { input ->
                val buf = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(16_384)
                var total = 0L
                while (true) {
                    val n = input.read(chunk)
                    if (n <= 0) break
                    total += n
                    if (total > MAX_AUTO_FETCH_BYTES) {
                        throw IOException("HTTP download exceeds $MAX_AUTO_FETCH_BYTES bytes")
                    }
                    buf.write(chunk, 0, n)
                }
                buf.toByteArray()
            }
            val plain = if (parsed.iv != null && parsed.key != null) {
                decryptAesGcm(cipherBytes, parsed.iv, parsed.key)
            } else {
                cipherBytes
            }
            destination.parentFile?.mkdirs()
            destination.outputStream().use { it.write(plain) }
            destination
        } finally {
            urlConnection.disconnect()
        }
    }.onFailure { Timber.w(it, "XMPP media download failed for %s", url) }

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

    private fun attachOriginId(message: SmackMessage) {
        if (OriginIdElement.hasOriginId(message)) return
        val id = message.stanzaId?.takeIf { it.isNotBlank() } ?: return
        message.addExtension(OriginIdElement(id))
    }

    /** XEP-0424: fastening that wraps a retract for [originId], if present. */
    fun extractRetractionOriginId(message: SmackMessage): String? {
        if (!FasteningElement.hasFasteningElement(message)) return null
        val fastening = message.getExtension(FasteningElement::class.java) ?: return null
        val hasRetract = fastening.wrappedPayloads.any { it is RetractElement }
        if (!hasRetract) return null
        return fastening.referencedStanzasOriginId?.id?.takeIf { it.isNotBlank() }
    }

    companion object {
        private data class UploadFetch(
            val fetchUrl: URL,
            val iv: ByteArray?,
            val key: ByteArray?,
        )

        /** aesgcm://host/path#iv+keyhex → https GET + AES-GCM decrypt (XEP-0454). */
        private fun parseUploadFetch(raw: String): UploadFetch {
            if (raw.startsWith("aesgcm://", ignoreCase = true)) {
                val without = raw.removePrefix("aesgcm://").removePrefix("AESGCM://")
                val hash = without.lastIndexOf('#')
                val pathPart = if (hash >= 0) without.substring(0, hash) else without
                val frag = if (hash >= 0) without.substring(hash + 1) else ""
                val hex = frag.filter { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
                val material = hexToBytes(hex)
                if (material.size < 28) {
                    throw IOException("aesgcm fragment too short (${material.size} bytes)")
                }
                val iv = material.copyOfRange(0, 12)
                val key = material.copyOfRange(12, material.size)
                return UploadFetch(URL("https://$pathPart"), iv, key)
            }
            return UploadFetch(URL(raw), null, null)
        }

        private fun hexToBytes(hex: String): ByteArray {
            if (hex.length % 2 != 0) throw IOException("Odd aesgcm hex length")
            return ByteArray(hex.length / 2) { i ->
                hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }

        private fun decryptAesGcm(ciphertext: ByteArray, iv: ByteArray, key: ByteArray): ByteArray {
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                javax.crypto.Cipher.DECRYPT_MODE,
                javax.crypto.spec.SecretKeySpec(key, "AES"),
                javax.crypto.spec.GCMParameterSpec(128, iv),
            )
            return cipher.doFinal(ciphertext)
        }

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

        const val MAX_AUTO_FETCH_BYTES = 8L * 1024L * 1024L

        fun stableStanzaId(message: SmackMessage): String? {
            val stanzaIdExt = runCatching {
                org.jivesoftware.smackx.sid.element.StanzaIdElement.getStanzaId(message)?.id
            }.getOrNull()
            val originId = runCatching {
                org.jivesoftware.smackx.sid.element.OriginIdElement.getOriginId(message)?.id
            }.getOrNull()
            return stanzaIdExt?.takeIf { it.isNotBlank() }
                ?: originId?.takeIf { it.isNotBlank() }
                ?: message.stanzaId?.takeIf { it.isNotBlank() }
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
