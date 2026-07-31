package ltechnologies.onionphone.securemessenger.protocol.xmpp

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ltechnologies.onionphone.securemessenger.core.model.AccountCredentials
import ltechnologies.onionphone.securemessenger.core.model.Attachment
import ltechnologies.onionphone.securemessenger.core.model.AttachmentState
import ltechnologies.onionphone.securemessenger.core.model.ConnectionResult
import ltechnologies.onionphone.securemessenger.core.model.ConnectionState
import ltechnologies.onionphone.securemessenger.core.model.Conversation
import ltechnologies.onionphone.securemessenger.core.model.DeliveryState
import ltechnologies.onionphone.securemessenger.core.model.HistoryLoadResult
import ltechnologies.onionphone.securemessenger.core.model.Message
import ltechnologies.onionphone.securemessenger.core.model.MessageDirection
import ltechnologies.onionphone.securemessenger.core.model.MessageKind
import ltechnologies.onionphone.securemessenger.core.model.OutgoingContent
import ltechnologies.onionphone.securemessenger.core.model.ProtocolCapabilities
import ltechnologies.onionphone.securemessenger.core.model.ProtocolId
import ltechnologies.onionphone.securemessenger.core.model.ProxyConfig
import ltechnologies.onionphone.securemessenger.core.model.RegistrationField
import ltechnologies.onionphone.securemessenger.core.model.RegistrationRequest
import ltechnologies.onionphone.securemessenger.core.model.RegistrationResult
import ltechnologies.onionphone.securemessenger.core.model.SanitizedText
import ltechnologies.onionphone.securemessenger.core.model.SendResult
import ltechnologies.onionphone.securemessenger.core.network.NetworkGuard
import ltechnologies.onionphone.securemessenger.data.MessengerRepository
import ltechnologies.onionphone.securemessenger.protocol.api.MessengerProtocol
import org.jivesoftware.smack.SmackException
import org.jivesoftware.smack.packet.Message as SmackMessage
import org.jivesoftware.smack.roster.RosterListener
import org.jivesoftware.smack.packet.Presence
import org.jivesoftware.smackx.chat_markers.ChatMarkersState
import org.jivesoftware.smackx.chat_markers.element.ChatMarkersElements
import org.jivesoftware.smackx.chatstates.ChatState
import org.jivesoftware.smackx.geoloc.packet.GeoLocation
import org.jxmpp.jid.Jid
import org.json.JSONObject
import timber.log.Timber

@Singleton
class XmppProtocol @Inject constructor(
    @ApplicationContext private val context: Context,
    private val networkGuard: NetworkGuard,
    private val repository: MessengerRepository,
) : MessengerProtocol {

    override val id: ProtocolId = ProtocolId.XMPP

    override val capabilities = ProtocolCapabilities(
        directMessages = true,
        groupChats = true,
        mediaSend = true,
        mediaReceive = true,
        typingIndicators = true,
        readReceipts = true,
        endToEndEncryption = true,
        contacts = true,
        profileEdit = true,
        voiceNotes = true,
        stickers = false,
        gifs = true,
        locationShare = true,
        polls = false,
        contactShare = true,
        ephemeralMessages = false,
        messageHistory = true,
        backupExport = true,
    )

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val registration = XmppRegistration(context)

    /**
     * Each connected XMPP account gets its own [SmackClientFacade] (and therefore its own
     * [org.jivesoftware.smack.tcp.XMPPTCPConnection]). Keying by accountId lets two or more XMPP
     * accounts stay connected simultaneously — connecting account B must never tear down account
     * A's live connection, which was the root cause of "Bob's inbox shows Bob talking to
     * himself" style bugs when only a single shared connection field existed.
     */
    private val sessions = ConcurrentHashMap<String, SmackClientFacade>()

    /** conversationId → display names / JIDs of peers currently composing. */
    private val typingFlows = ConcurrentHashMap<String, MutableStateFlow<List<String>>>()

    /** Exposes the underlying Smack facade for [accountId] (or the sole connected one if omitted). */
    fun smackFacade(accountId: String? = null): SmackClientFacade? =
        accountId?.let { sessions[it] } ?: sessions.values.singleOrNull()

    override fun isAccountConnected(accountId: String): Boolean =
        sessions[accountId]?.isConnected() == true

    override val canRegister: Boolean = true

    override suspend fun register(request: RegistrationRequest, proxy: ProxyConfig): RegistrationResult =
        withContext(Dispatchers.IO) {
            try {
                networkGuard.assertNetworkAllowed()
                val server = request.server.trim()
                val username = request.username.trim()
                if (server.isBlank()) return@withContext RegistrationResult.Failure("Missing server domain")
                if (username.isBlank()) return@withContext RegistrationResult.Failure("Missing username")

                if (request.extraFields.isEmpty()) {
                    val requirements = registration.fetchRequirements(server, proxy).getOrElse {
                        return@withContext RegistrationResult.Failure(
                            it.message ?: "Could not reach $server",
                        )
                    }
                    if (!requirements.supported) {
                        return@withContext RegistrationResult.Failure(
                            "$server does not support in-band account registration",
                        )
                    }
                    if (requirements.requiredAttributes.isNotEmpty()) {
                        val sessionId = java.util.UUID.randomUUID().toString()
                        registration.rememberPending(sessionId, server, username, request.password)
                        return@withContext RegistrationResult.NeedsFields(
                            sessionId,
                            requirements.requiredAttributes.map { key ->
                                RegistrationField(key, key.replaceFirstChar { it.uppercase() })
                            },
                            instructions = requirements.instructions,
                        )
                    }
                }
                registration.register(server, username, request.password, request.extraFields, proxy)
            } catch (e: Exception) {
                Timber.w(e, "XMPP registration failed")
                RegistrationResult.Failure(e.message ?: "XMPP registration failed")
            }
        }

    override suspend fun continueRegistration(
        sessionId: String,
        fields: Map<String, String>,
        proxy: ProxyConfig,
    ): RegistrationResult =
        withContext(Dispatchers.IO) {
            try {
                networkGuard.assertNetworkAllowed()
                val (server, username, password) = registration.consumePending(sessionId)
                    ?: return@withContext RegistrationResult.Failure(
                        "Registration session expired — please restart registration",
                    )
                registration.register(server, username, password, fields, proxy)
            } catch (e: Exception) {
                Timber.w(e, "XMPP registration (continue) failed")
                RegistrationResult.Failure(e.message ?: "XMPP registration failed")
            }
        }

    override suspend fun connect(account: AccountCredentials, proxy: ProxyConfig): ConnectionResult =
        withContext(Dispatchers.IO) {
            try {
                networkGuard.assertNetworkAllowed()
                _connectionState.value = ConnectionState.CONNECTING

                val jid = account.secrets["jid"] ?: return@withContext ConnectionResult.Failure("Missing JID")
                val password = account.secrets["password"]
                    ?: return@withContext ConnectionResult.Failure("Missing password")
                val server = account.secrets["server"]

                // Reconnecting the SAME account: tear down its old session only, leaving any
                // other simultaneously-connected accounts of this protocol untouched.
                sessions.remove(account.accountId)?.disconnect()

                val smack = SmackClientFacade(context)
                smack.connect(jid, password, server, proxy)
                sessions[account.accountId] = smack

                smack.chatManager?.addIncomingListener { from, message, _ ->
                    scope.launch {
                        handleIncoming(account.accountId, smack, from.toString(), message)
                    }
                }

                smack.addChatStateListener { chat, state, _ ->
                    val peer = chat.xmppAddressOfChatPartner?.asBareJid()?.toString()
                        ?: return@addChatStateListener
                    val convId = conversationId(account.accountId, peer)
                    val flow = typingFlows.getOrPut(convId) { MutableStateFlow(emptyList()) }
                    val label = smack.rosterEntries()
                        .firstOrNull { SmackClientFacade.rosterJidString(it) == peer }
                        ?.name
                        ?.takeIf { it.isNotBlank() }
                        ?: peer
                    when (state) {
                        ChatState.composing -> flow.value = listOf(label)
                        ChatState.active, ChatState.paused, ChatState.inactive, ChatState.gone ->
                            flow.value = emptyList()
                    }
                }

                smack.addReceiptReceivedListener { from, _, receiptId, _ ->
                    scope.launch {
                        updateDeliveryState(
                            account.accountId,
                            from.asEntityBareJidIfPossible()?.toString() ?: from.asBareJid().toString(),
                            receiptId,
                            DeliveryState.DELIVERED,
                        )
                    }
                }

                smack.addChatMarkerListener { state, message, chat ->
                    if (state != ChatMarkersState.displayed) return@addChatMarkerListener
                    val displayedId = ChatMarkersElements.DisplayedExtension.from(message)?.id ?: return@addChatMarkerListener
                    val peer = chat?.xmppAddressOfChatPartner?.toString()
                        ?: message.from?.asBareJid()?.toString()
                        ?: return@addChatMarkerListener
                    scope.launch {
                        updateDeliveryState(account.accountId, peer, displayedId, DeliveryState.READ)
                    }
                }

                smack.roster?.addRosterListener(object : RosterListener {
                    override fun entriesAdded(addresses: MutableCollection<Jid>?) {
                        scope.launch { syncRoster(account.accountId, smack) }
                    }

                    override fun entriesUpdated(addresses: MutableCollection<Jid>?) {
                        scope.launch { syncRoster(account.accountId, smack) }
                    }

                    override fun entriesDeleted(addresses: MutableCollection<Jid>?) = Unit

                    override fun presenceChanged(presence: Presence?) = Unit
                })

                syncRoster(account.accountId, smack)

                scope.launch {
                    smack.rosterEntries().forEach { entry ->
                        val remote = SmackClientFacade.rosterJidString(entry)
                        XmppMamSync.syncHistory(smack, account.accountId, repository, remote)
                    }
                }

                repository.upsertAccount(
                    ltechnologies.onionphone.securemessenger.core.model.Account(
                        id = account.accountId,
                        protocol = ProtocolId.XMPP,
                        displayName = account.displayName,
                        connectionState = ConnectionState.CONNECTED,
                    ),
                )
                _connectionState.value = ConnectionState.CONNECTED
                ConnectionResult.Success
            } catch (e: Exception) {
                Timber.w(e, "XMPP connect failed")
                _connectionState.value = ConnectionState.ERROR
                ConnectionResult.Failure(e.message ?: "XMPP connection failed")
            }
        }

    private suspend fun syncRoster(accId: String, smack: SmackClientFacade) {
        val rosterEntries = smack.rosterEntries()
        val conversations = rosterEntries.map { entry ->
            val remote = SmackClientFacade.rosterJidString(entry)
            Conversation(
                id = conversationId(accId, remote),
                protocol = ProtocolId.XMPP,
                accountId = accId,
                remoteId = remote,
                title = entry.name ?: remote,
                lastMessagePreview = null,
                lastMessageAt = 0L,
                unreadCount = 0,
            )
        }.toMutableList()

        val contacts = rosterEntries.map { entry ->
            val remote = SmackClientFacade.rosterJidString(entry)
            ltechnologies.onionphone.securemessenger.core.model.Contact(
                id = "${accId}_$remote",
                protocol = ProtocolId.XMPP,
                accountId = accId,
                remoteId = remote,
                displayName = entry.name?.takeIf { it.isNotBlank() } ?: remote.substringBefore('@'),
                handle = remote,
            )
        }
        repository.replaceContacts(accId, contacts)

        val defaultNick = smack.myBareJid()?.substringBefore('@') ?: "SecureMessenger"
        smack.bookmarkedConferences().forEach { conference ->
            val roomJid = conference.jid.toString()
            val nickname = conference.nickname?.toString() ?: defaultNick
            runCatching {
                smack.joinMuc(roomJid, nickname) { message ->
                    scope.launch { handleIncoming(accId, smack, roomJid, message) }
                }
            }.onFailure { e ->
                Timber.w(e, "Failed to join bookmarked MUC $roomJid")
            }
            conversations.add(
                Conversation(
                    id = conversationId(accId, roomJid),
                    protocol = ProtocolId.XMPP,
                    accountId = accId,
                    remoteId = roomJid,
                    title = conference.name?.takeIf { it.isNotBlank() } ?: roomJid,
                    lastMessagePreview = null,
                    lastMessageAt = 0L,
                    unreadCount = 0,
                ),
            )
        }

        repository.upsertConversations(conversations)
    }

    private suspend fun handleIncoming(
        accId: String,
        smack: SmackClientFacade,
        remoteJid: String,
        smackMessage: SmackMessage,
    ) {
        val geo = GeoLocation.from(smackMessage)
        if (geo != null && geo.lat != null && geo.lon != null) {
            persistStructuredIncoming(
                accId = accId,
                remoteJid = remoteJid,
                smackMessage = smackMessage,
                body = "📍 ${geo.lat}, ${geo.lon}",
                kind = MessageKind.LOCATION,
                payloadJson = JSONObject()
                    .put("latitude", geo.lat)
                    .put("longitude", geo.lon)
                    .put("accuracy", geo.accuracy ?: 0.0)
                    .toString(),
                outgoing = isOutgoing(smack, smackMessage),
            )
            return
        }

        val hasOmemo = smack.omemoHelper?.hasOmemoPayload(smackMessage) == true
        val omemoBody = if (hasOmemo) smack.omemoHelper?.tryDecrypt(remoteJid, smackMessage) else null
        val body = when {
            hasOmemo -> omemoBody ?: return // fail-closed: never store plaintext beside broken ciphertext
            else -> smackMessage.body ?: return
        }
        val convId = conversationId(accId, remoteJid)
        val ts = SmackClientFacade.extractDelayTimestamp(smackMessage) ?: System.currentTimeMillis()
        val myJid = smack.myBareJid()
        val outgoing = isOutgoing(smack, smackMessage)
        val uploadUrl = SmackClientFacade.extractHttpUploadUrl(body)
            ?: SmackClientFacade.extractOobUrl(smackMessage)
        val attachments = uploadUrl?.let { url ->
            listOf(
                Attachment(
                    id = "${convId}_${smackMessage.stanzaId ?: ts}_file",
                    mimeType = guessMimeFromUrl(url),
                    fileName = url.substringAfterLast('/').takeIf { it.isNotBlank() },
                    remoteRef = url,
                    state = AttachmentState.READY,
                ),
            )
        } ?: emptyList()
        val kind = when {
            attachments.isNotEmpty() && attachments.first().mimeType.startsWith("audio/") -> MessageKind.VOICE
            attachments.isNotEmpty() && attachments.first().mimeType == "image/gif" -> MessageKind.GIF
            attachments.isNotEmpty() && attachments.first().mimeType.startsWith("image/") -> MessageKind.IMAGE
            attachments.isNotEmpty() && attachments.first().mimeType.startsWith("video/") -> MessageKind.VIDEO
            attachments.isNotEmpty() -> MessageKind.FILE
            body.trimStart().startsWith("BEGIN:VCARD", ignoreCase = true) -> MessageKind.CONTACT
            else -> MessageKind.TEXT
        }
        val displayBody = when {
            kind == MessageKind.CONTACT -> {
                body.lineSequence()
                    .firstOrNull { it.startsWith("FN:", ignoreCase = true) }
                    ?.substringAfter(':')
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: "Contact"
            }
            uploadUrl != null && attachments.isNotEmpty() -> attachments.first().fileName ?: "File"
            else -> body
        }
        val payloadJson = if (kind == MessageKind.CONTACT) {
            JSONObject().put("vcard", body).toString()
        } else {
            null
        }
        val msg = Message(
            id = "${convId}_${smackMessage.stanzaId ?: ts}",
            conversationId = convId,
            protocol = ProtocolId.XMPP,
            body = displayBody,
            timestamp = ts,
            direction = if (outgoing) MessageDirection.OUTGOING else MessageDirection.INCOMING,
            deliveryState = DeliveryState.DELIVERED,
            senderDisplayName = if (outgoing) myJid else remoteJid,
            attachments = attachments,
            kind = kind,
            payloadJson = payloadJson,
        )
        repository.upsertMessage(msg)
        repository.upsertConversation(
            Conversation(
                id = convId,
                protocol = ProtocolId.XMPP,
                accountId = accId,
                remoteId = remoteJid,
                title = remoteJid,
                lastMessagePreview = displayBody.take(100),
                lastMessageAt = ts,
                unreadCount = if (outgoing) 0 else 1,
            ),
        )
    }

    private fun isOutgoing(smack: SmackClientFacade, smackMessage: SmackMessage): Boolean {
        val myJid = smack.myBareJid()
        return SmackClientFacade.isCarbonSent(smackMessage) ||
            smackMessage.from?.asBareJid()?.toString() == myJid
    }

    private suspend fun persistStructuredIncoming(
        accId: String,
        remoteJid: String,
        smackMessage: SmackMessage,
        body: String,
        kind: MessageKind,
        payloadJson: String?,
        outgoing: Boolean,
    ) {
        val convId = conversationId(accId, remoteJid)
        val ts = SmackClientFacade.extractDelayTimestamp(smackMessage) ?: System.currentTimeMillis()
        val msg = Message(
            id = "${convId}_${smackMessage.stanzaId ?: ts}",
            conversationId = convId,
            protocol = ProtocolId.XMPP,
            body = body,
            timestamp = ts,
            direction = if (outgoing) MessageDirection.OUTGOING else MessageDirection.INCOMING,
            deliveryState = DeliveryState.DELIVERED,
            senderDisplayName = if (outgoing) smackMessage.from?.asBareJid()?.toString() else remoteJid,
            kind = kind,
            payloadJson = payloadJson,
        )
        repository.upsertMessage(msg)
        repository.upsertConversation(
            Conversation(
                id = convId,
                protocol = ProtocolId.XMPP,
                accountId = accId,
                remoteId = remoteJid,
                title = remoteJid,
                lastMessagePreview = body.take(100),
                lastMessageAt = ts,
                unreadCount = if (outgoing) 0 else 1,
            ),
        )
    }

    private suspend fun updateDeliveryState(
        accId: String,
        remoteJid: String,
        stanzaId: String,
        state: DeliveryState,
    ) {
        if (stanzaId.isBlank()) return
        val convId = conversationId(accId, remoteJid)
        val messageKey = "${convId}_$stanzaId"
        val target = repository.getMessage(messageKey)
            ?: return
        val ranked = mapOf(
            DeliveryState.PENDING to 0,
            DeliveryState.SENT to 1,
            DeliveryState.DELIVERED to 2,
            DeliveryState.READ to 3,
            DeliveryState.FAILED to -1,
        )
        val currentRank = ranked[target.deliveryState] ?: 0
        val nextRank = ranked[state] ?: 0
        if (nextRank < currentRank) return
        repository.upsertMessage(target.copy(deliveryState = state))
    }

    private fun guessMimeFromUrl(url: String): String {
        val path = url.substringBefore('#').substringBefore('?').lowercase()
        return when {
            path.endsWith(".ogg") || path.endsWith(".opus") -> "audio/ogg"
            path.endsWith(".gif") -> "image/gif"
            path.endsWith(".png") -> "image/png"
            path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
            path.endsWith(".webp") -> "image/webp"
            path.endsWith(".mp4") -> "video/mp4"
            path.endsWith(".m4a") -> "audio/mp4"
            else -> "application/octet-stream"
        }
    }

    override fun observeConversations(): Flow<List<Conversation>> = repository.observeConversations()

    override fun observeMessages(conversationId: String): Flow<List<Message>> =
        repository.observeMessages(conversationId)

    override suspend fun startConversation(
        remoteId: String,
        initialMessage: SanitizedText?,
        accountId: String?,
        asGroup: Boolean,
    ): SendResult =
        withContext(Dispatchers.IO) {
            val accId = accountId ?: sessions.keys.singleOrNull()
                ?: return@withContext SendResult.Failure("Not connected")
            val smack = sessions[accId]
                ?: return@withContext SendResult.Failure("Account not connected")
            networkGuard.assertNetworkAllowed()
            if (asGroup || SmackClientFacade.isLikelyMucJid(remoteId) || smack.isMucRoom(remoteId)) {
                val nickname = smack.myBareJid()?.substringBefore('@') ?: "SecureMessenger"
                runCatching {
                    smack.joinMuc(remoteId, nickname) { message ->
                        scope.launch { handleIncoming(accId, smack, remoteId, message) }
                    }
                }.onFailure { e ->
                    return@withContext SendResult.Failure(e.message ?: "Impossible de rejoindre la salle MUC")
                }
            }
            val convId = conversationId(accId, remoteId)
            repository.upsertConversation(
                Conversation(
                    id = convId,
                    protocol = ProtocolId.XMPP,
                    accountId = accId,
                    remoteId = remoteId,
                    title = remoteId,
                ),
            )
            if (initialMessage != null) {
                val send = sendMessage(convId, initialMessage, accId)
                if (send is SendResult.Failure) return@withContext send
            }
            SendResult.Success(convId)
        }

    override suspend fun sendMessage(conversationId: String, body: SanitizedText, accountId: String?): SendResult =
        withContext(Dispatchers.IO) {
            try {
                networkGuard.assertNetworkAllowed()
                val accId = accountId ?: conversationId.substringBefore('_', missingDelimiterValue = conversationId)
                val smack = sessions[accId]
                    ?: return@withContext SendResult.Failure("Account not connected")
                val remoteJid = conversationId.substringAfter('_', missingDelimiterValue = conversationId)
                val stanzaId = if (smack.isMucRoom(remoteJid)) {
                    smack.sendMucMessage(remoteJid, body.value)
                } else {
                    smack.sendChatMessage(remoteJid, body.value, requestReceipts = true)
                }
                val localId = if (stanzaId.isNotBlank()) {
                    "${conversationId}_$stanzaId"
                } else {
                    "${conversationId}_${System.currentTimeMillis()}"
                }
                val msg = Message(
                    id = localId,
                    conversationId = conversationId,
                    protocol = ProtocolId.XMPP,
                    body = body.value,
                    timestamp = System.currentTimeMillis(),
                    direction = MessageDirection.OUTGOING,
                    deliveryState = DeliveryState.SENT,
                    kind = MessageKind.TEXT,
                )
                repository.upsertMessage(msg)
                SendResult.Success(msg.id)
            } catch (e: SmackException.NotConnectedException) {
                SendResult.Failure("Not connected")
            } catch (e: Exception) {
                SendResult.Failure(e.message ?: "Send failed")
            }
        }

    override suspend fun sendMedia(
        conversationId: String,
        attachment: Attachment,
        caption: SanitizedText?,
        accountId: String?,
    ): SendResult = sendMediaInternal(
        conversationId = conversationId,
        attachment = attachment,
        caption = caption,
        accountId = accountId,
        kind = mimeToKind(attachment.mimeType),
        contentType = attachment.mimeType.takeIf { it.isNotBlank() },
    )

    private suspend fun sendMediaInternal(
        conversationId: String,
        attachment: Attachment,
        caption: SanitizedText?,
        accountId: String?,
        kind: MessageKind,
        contentType: String?,
    ): SendResult = withContext(Dispatchers.IO) {
        try {
            networkGuard.assertNetworkAllowed()
            val accId = accountId ?: conversationId.substringBefore('_', missingDelimiterValue = conversationId)
            val smack = sessions[accId]
                ?: return@withContext SendResult.Failure("Account not connected")
            val remoteJid = conversationId.substringAfter('_', missingDelimiterValue = conversationId)
            val localPath = attachment.localPath
                ?: return@withContext SendResult.Failure("Missing local file path")
            val file = java.io.File(localPath)
            if (!file.exists()) {
                return@withContext SendResult.Failure("File not found")
            }
            val uploadUrl = smack.uploadFile(file, contentType)
            val preview = caption?.value?.takeIf { it.isNotBlank() }
                ?: attachment.fileName
                ?: file.name
            val stanzaId = if (smack.isMucRoom(remoteJid)) {
                smack.sendMucMessage(remoteJid, uploadUrl.toString())
            } else {
                smack.sendChatMessage(remoteJid, uploadUrl.toString(), requestReceipts = true)
            }
            val localId = if (stanzaId.isNotBlank()) {
                "${conversationId}_$stanzaId"
            } else {
                "${conversationId}_${System.currentTimeMillis()}"
            }
            val msg = Message(
                id = localId,
                conversationId = conversationId,
                protocol = ProtocolId.XMPP,
                body = preview,
                timestamp = System.currentTimeMillis(),
                direction = MessageDirection.OUTGOING,
                deliveryState = DeliveryState.SENT,
                attachments = listOf(
                    attachment.copy(
                        mimeType = contentType ?: attachment.mimeType,
                        remoteRef = uploadUrl.toString(),
                        state = AttachmentState.READY,
                    ),
                ),
                kind = kind,
            )
            repository.upsertMessage(msg)
            SendResult.Success(msg.id)
        } catch (e: SmackException.NotConnectedException) {
            SendResult.Failure("Not connected")
        } catch (e: Exception) {
            SendResult.Failure(e.message ?: "Media send failed")
        }
    }

    private fun mimeToKind(mime: String): MessageKind = when {
        mime.equals("image/gif", ignoreCase = true) -> MessageKind.GIF
        mime.startsWith("image/") -> MessageKind.IMAGE
        mime.startsWith("video/") -> MessageKind.VIDEO
        mime.startsWith("audio/") -> MessageKind.VOICE
        else -> MessageKind.FILE
    }

    override suspend fun sendContent(
        conversationId: String,
        content: OutgoingContent,
        accountId: String?,
    ): SendResult = when (content) {
        is OutgoingContent.Text -> sendMessage(conversationId, content.body, accountId)
        is OutgoingContent.Media -> {
            val mime = when (content.kind) {
                MessageKind.GIF -> content.attachment.mimeType.ifBlank { "image/gif" }
                MessageKind.IMAGE -> content.attachment.mimeType.ifBlank { "image/jpeg" }
                MessageKind.VIDEO -> content.attachment.mimeType.ifBlank { "video/mp4" }
                MessageKind.VOICE -> content.attachment.mimeType.ifBlank { "audio/ogg" }
                else -> content.attachment.mimeType
            }
            sendMediaInternal(
                conversationId = conversationId,
                attachment = content.attachment.copy(mimeType = mime),
                caption = content.caption,
                accountId = accountId,
                kind = content.kind,
                contentType = mime,
            )
        }
        is OutgoingContent.VoiceNote -> {
            val mime = content.attachment.mimeType.ifBlank { "audio/ogg" }
            sendMediaInternal(
                conversationId = conversationId,
                attachment = content.attachment.copy(mimeType = mime),
                caption = null,
                accountId = accountId,
                kind = MessageKind.VOICE,
                contentType = "audio/ogg",
            )
        }
        is OutgoingContent.Location -> sendLocationContent(conversationId, content, accountId)
        is OutgoingContent.ContactCard -> sendContactContent(conversationId, content, accountId)
        is OutgoingContent.Poll ->
            SendResult.Failure("Les sondages ne sont pas supportés en XMPP")
        is OutgoingContent.Sticker ->
            SendResult.Failure("Les stickers ne sont pas supportés en XMPP")
        is OutgoingContent.Ephemeral ->
            SendResult.Failure("Les messages éphémères ne sont pas supportés en XMPP")
    }

    private suspend fun sendLocationContent(
        conversationId: String,
        content: OutgoingContent.Location,
        accountId: String?,
    ): SendResult = withContext(Dispatchers.IO) {
        try {
            networkGuard.assertNetworkAllowed()
            val accId = accountId ?: conversationId.substringBefore('_', missingDelimiterValue = conversationId)
            val smack = sessions[accId]
                ?: return@withContext SendResult.Failure("Account not connected")
            val remoteJid = conversationId.substringAfter('_', missingDelimiterValue = conversationId)
            if (smack.isMucRoom(remoteJid)) {
                // GeoLocationManager targets a JID; for MUC send a geo URI body as fallback.
                val body = "geo:${content.latitude},${content.longitude}"
                val stanzaId = smack.sendMucMessage(remoteJid, body)
                val localId = "${conversationId}_${stanzaId.ifBlank { System.currentTimeMillis().toString() }}"
                val msg = Message(
                    id = localId,
                    conversationId = conversationId,
                    protocol = ProtocolId.XMPP,
                    body = "📍 ${content.latitude}, ${content.longitude}",
                    timestamp = System.currentTimeMillis(),
                    direction = MessageDirection.OUTGOING,
                    deliveryState = DeliveryState.SENT,
                    kind = MessageKind.LOCATION,
                    payloadJson = JSONObject()
                        .put("latitude", content.latitude)
                        .put("longitude", content.longitude)
                        .put("accuracy", content.horizontalAccuracy)
                        .toString(),
                )
                repository.upsertMessage(msg)
                return@withContext SendResult.Success(msg.id)
            }
            smack.sendGeoLocation(
                remoteJid,
                content.latitude,
                content.longitude,
                content.horizontalAccuracy,
            )
            val localId = "${conversationId}_${System.currentTimeMillis()}"
            val msg = Message(
                id = localId,
                conversationId = conversationId,
                protocol = ProtocolId.XMPP,
                body = "📍 ${content.latitude}, ${content.longitude}",
                timestamp = System.currentTimeMillis(),
                direction = MessageDirection.OUTGOING,
                deliveryState = DeliveryState.SENT,
                kind = MessageKind.LOCATION,
                payloadJson = JSONObject()
                    .put("latitude", content.latitude)
                    .put("longitude", content.longitude)
                    .put("accuracy", content.horizontalAccuracy)
                    .toString(),
            )
            repository.upsertMessage(msg)
            SendResult.Success(msg.id)
        } catch (e: SmackException.NotConnectedException) {
            SendResult.Failure("Not connected")
        } catch (e: Exception) {
            SendResult.Failure(e.message ?: "Location send failed")
        }
    }

    private suspend fun sendContactContent(
        conversationId: String,
        content: OutgoingContent.ContactCard,
        accountId: String?,
    ): SendResult = withContext(Dispatchers.IO) {
        try {
            networkGuard.assertNetworkAllowed()
            val accId = accountId ?: conversationId.substringBefore('_', missingDelimiterValue = conversationId)
            val smack = sessions[accId]
                ?: return@withContext SendResult.Failure("Account not connected")
            val remoteJid = conversationId.substringAfter('_', missingDelimiterValue = conversationId)
            val vcard = SmackClientFacade.formatContactVCard(
                content.firstName,
                content.lastName,
                content.phone,
            )
            val stanzaId = if (smack.isMucRoom(remoteJid)) {
                smack.sendMucMessage(remoteJid, vcard)
            } else {
                smack.sendChatMessage(remoteJid, vcard, requestReceipts = true)
            }
            val display = listOf(content.firstName, content.lastName)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .ifBlank { content.phone ?: "Contact" }
            val localId = "${conversationId}_${stanzaId.ifBlank { System.currentTimeMillis().toString() }}"
            val msg = Message(
                id = localId,
                conversationId = conversationId,
                protocol = ProtocolId.XMPP,
                body = display,
                timestamp = System.currentTimeMillis(),
                direction = MessageDirection.OUTGOING,
                deliveryState = DeliveryState.SENT,
                kind = MessageKind.CONTACT,
                payloadJson = JSONObject()
                    .put("firstName", content.firstName)
                    .put("lastName", content.lastName)
                    .put("phone", content.phone ?: "")
                    .put("vcard", vcard)
                    .toString(),
            )
            repository.upsertMessage(msg)
            SendResult.Success(msg.id)
        } catch (e: SmackException.NotConnectedException) {
            SendResult.Failure("Not connected")
        } catch (e: Exception) {
            SendResult.Failure(e.message ?: "Contact send failed")
        }
    }

    override suspend fun loadMessageHistory(conversationId: String): HistoryLoadResult =
        withContext(Dispatchers.IO) {
            try {
                networkGuard.assertNetworkAllowed()
                val accId = conversationId.substringBefore('_', missingDelimiterValue = conversationId)
                val remoteJid = conversationId.substringAfter('_', missingDelimiterValue = conversationId)
                val smack = sessions[accId]
                    ?: return@withContext HistoryLoadResult.Failure("Compte XMPP non connecté")
                val synced = XmppMamSync.syncHistory(smack, accId, repository, remoteJid)
                val count = repository.countMessages(conversationId)
                HistoryLoadResult.Success(
                    messageCount = count,
                    loadedFromCache = count > 0,
                    syncedFromNetwork = synced,
                )
            } catch (e: Exception) {
                HistoryLoadResult.Failure(e.message ?: "Historique XMPP indisponible")
            }
        }

    override fun observeContacts(accountId: String): Flow<List<ltechnologies.onionphone.securemessenger.core.model.Contact>> =
        repository.observeContacts(accountId)

    override suspend fun refreshContacts(accountId: String): Result<Int> = withContext(Dispatchers.IO) {
        val smack = sessions[accountId] ?: return@withContext Result.failure(IllegalStateException("XMPP non connecté"))
        runCatching {
            syncRoster(accountId, smack)
            repository.observeContacts(accountId).first().size
        }
    }

    override suspend fun getAccountProfile(accountId: String): ltechnologies.onionphone.securemessenger.core.model.AccountProfile? {
        val smack = sessions[accountId] ?: return null
        val jid = smack.myBareJid() ?: accountId
        return withContext(Dispatchers.IO) {
            val vcard = runCatching { smack.loadOwnVCard() }.getOrNull()
            val nick = vcard?.nickName?.takeIf { it.isNotBlank() }
            val fullName = listOfNotNull(
                vcard?.firstName?.takeIf { it.isNotBlank() },
                vcard?.lastName?.takeIf { it.isNotBlank() },
            ).joinToString(" ").takeIf { it.isNotBlank() }
            ltechnologies.onionphone.securemessenger.core.model.AccountProfile(
                accountId = accountId,
                protocol = ProtocolId.XMPP,
                displayName = nick ?: fullName ?: jid.substringBefore('@'),
                handle = jid,
                bio = vcard?.getField("DESC")?.takeIf { it.isNotBlank() },
            )
        }
    }

    override suspend fun updateAccountProfile(
        accountId: String,
        displayName: String,
        bio: String?,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val smack = sessions[accountId]
            ?: return@withContext Result.failure(IllegalStateException("XMPP non connecté"))
        runCatching {
            networkGuard.assertNetworkAllowed()
            smack.saveOwnVCard(displayName, bio)
        }
    }

    override fun observeTyping(conversationId: String): StateFlow<List<String>> =
        typingFlows.getOrPut(conversationId) { MutableStateFlow(emptyList()) }.asStateFlow()

    override suspend fun setTyping(conversationId: String, typing: Boolean) {
        withContext(Dispatchers.IO) {
            val accId = conversationId.substringBefore('_', missingDelimiterValue = conversationId)
            val remoteJid = conversationId.substringAfter('_', missingDelimiterValue = conversationId)
            val smack = sessions[accId] ?: return@withContext
            if (smack.isMucRoom(remoteJid)) return@withContext
            runCatching { smack.setTyping(remoteJid, typing) }
                .onFailure { Timber.d(it, "XMPP typing failed") }
        }
    }

    override suspend fun markRead(conversationId: String, messageId: String?) {
        withContext(Dispatchers.IO) {
            val accId = conversationId.substringBefore('_', missingDelimiterValue = conversationId)
            val remoteJid = conversationId.substringAfter('_', missingDelimiterValue = conversationId)
            val smack = sessions[accId] ?: return@withContext
            if (smack.isMucRoom(remoteJid)) return@withContext
            val stanzaId = messageId?.removePrefix("${conversationId}_")
                ?: repository.latestMessageId(conversationId)?.removePrefix("${conversationId}_")
                ?: return@withContext
            if (stanzaId.isBlank()) return@withContext
            runCatching { smack.markDisplayed(remoteJid, stanzaId) }
                .onFailure { Timber.d(it, "XMPP markRead failed") }
        }
    }

    override suspend fun exportBackup(
        accountId: String,
        destinationPath: String,
    ): ltechnologies.onionphone.securemessenger.core.model.BackupExportResult = withContext(Dispatchers.IO) {
        runCatching {
            val (convs, messages) = repository.exportSnapshot(accountId)
            val json = buildString {
                append("{\"protocol\":\"XMPP\",\"accountId\":")
                append(org.json.JSONObject.quote(accountId))
                append(",\"exportedAt\":")
                append(System.currentTimeMillis())
                append(",\"conversations\":[")
                convs.forEachIndexed { i, c ->
                    if (i > 0) append(',')
                    append("{\"id\":").append(org.json.JSONObject.quote(c.id))
                    append(",\"title\":").append(org.json.JSONObject.quote(c.title))
                    append(",\"remoteId\":").append(org.json.JSONObject.quote(c.remoteId))
                    append('}')
                }
                append("],\"messages\":[")
                messages.forEachIndexed { i, m ->
                    if (i > 0) append(',')
                    append("{\"id\":").append(org.json.JSONObject.quote(m.id))
                    append(",\"conversationId\":").append(org.json.JSONObject.quote(m.conversationId))
                    append(",\"body\":").append(org.json.JSONObject.quote(m.body))
                    append(",\"timestamp\":").append(m.timestamp)
                    append(",\"direction\":").append(org.json.JSONObject.quote(m.direction.name))
                    append(",\"kind\":").append(org.json.JSONObject.quote(m.kind.name))
                    append('}')
                }
                append("]}")
            }
            java.io.File(destinationPath).writeText(json)
            ltechnologies.onionphone.securemessenger.core.model.BackupExportResult.Success(
                uriOrPath = destinationPath,
                messageCount = messages.size,
                conversationCount = convs.size,
            )
        }.getOrElse {
            ltechnologies.onionphone.securemessenger.core.model.BackupExportResult.Failure(
                it.message ?: "Export échoué",
            )
        }
    }

    override suspend fun disconnect(accountId: String?) {
        withContext(Dispatchers.IO) {
            val toClose = if (accountId != null) {
                sessions.remove(accountId)?.let { listOf(accountId to it) } ?: emptyList()
            } else {
                val all = sessions.entries.map { it.key to it.value }
                sessions.clear()
                all
            }
            toClose.forEach { (id, facade) ->
                facade.disconnect()
                typingFlows.keys.filter { it.startsWith("${id}_") }.forEach { typingFlows.remove(it) }
                repository.upsertAccount(
                    ltechnologies.onionphone.securemessenger.core.model.Account(
                        id = id,
                        protocol = ProtocolId.XMPP,
                        displayName = id,
                        connectionState = ConnectionState.DISCONNECTED,
                    ),
                )
            }
            if (accountId == null) {
                typingFlows.clear()
            }
            if (sessions.isEmpty()) {
                _connectionState.value = ConnectionState.DISCONNECTED
            }
        }
    }

    private fun conversationId(accountId: String, remoteJid: String) = "${accountId}_$remoteJid"

    companion object {
        fun conversationIdFor(accountId: String, remoteJid: String) = "${accountId}_$remoteJid"
    }
}

object XmppInitializer {
    @Volatile
    private var initialized = false

    fun ensureInitialized(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (!initialized) {
                org.jivesoftware.smack.android.AndroidSmackInitializer.initialize(context)
                initialized = true
            }
        }
    }
}
