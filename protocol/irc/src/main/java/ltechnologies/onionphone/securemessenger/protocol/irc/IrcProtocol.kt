package ltechnologies.onionphone.securemessenger.protocol.irc

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ltechnologies.onionphone.securemessenger.core.model.Account
import ltechnologies.onionphone.securemessenger.core.model.AccountCredentials
import ltechnologies.onionphone.securemessenger.core.model.AccountProfile
import ltechnologies.onionphone.securemessenger.core.model.BackupExportResult
import ltechnologies.onionphone.securemessenger.core.model.ConnectionResult
import ltechnologies.onionphone.securemessenger.core.model.ConnectionState
import ltechnologies.onionphone.securemessenger.core.model.Contact
import ltechnologies.onionphone.securemessenger.core.model.Conversation
import ltechnologies.onionphone.securemessenger.core.model.DeliveryState
import ltechnologies.onionphone.securemessenger.core.model.HistoryLoadResult
import ltechnologies.onionphone.securemessenger.core.model.Message
import ltechnologies.onionphone.securemessenger.core.model.MessageDirection
import ltechnologies.onionphone.securemessenger.core.model.MessageKind
import ltechnologies.onionphone.securemessenger.core.model.ProtocolCapabilities
import ltechnologies.onionphone.securemessenger.core.model.ProtocolId
import ltechnologies.onionphone.securemessenger.core.model.ProxyConfig
import ltechnologies.onionphone.securemessenger.core.model.SanitizedText
import ltechnologies.onionphone.securemessenger.core.model.SendResult
import ltechnologies.onionphone.securemessenger.core.network.NetworkGuard
import ltechnologies.onionphone.securemessenger.core.proxy.SocksEndpointResolver
import ltechnologies.onionphone.securemessenger.data.MessengerRepository
import ltechnologies.onionphone.securemessenger.protocol.api.MessengerProtocol
import net.engio.mbassy.listener.Handler
import org.json.JSONArray
import org.json.JSONObject
import org.kitteh.irc.client.library.Client
import org.kitteh.irc.client.library.element.MessageTag
import org.kitteh.irc.client.library.feature.CaseMapping
import org.kitteh.irc.client.library.event.capabilities.CapabilitiesAcknowledgedEvent
import org.kitteh.irc.client.library.event.capabilities.CapabilitiesRejectedEvent
import org.kitteh.irc.client.library.event.channel.ChannelCtcpEvent
import org.kitteh.irc.client.library.event.channel.ChannelInviteEvent
import org.kitteh.irc.client.library.event.channel.ChannelJoinEvent
import org.kitteh.irc.client.library.event.channel.ChannelKickEvent
import org.kitteh.irc.client.library.event.channel.ChannelMessageEvent
import org.kitteh.irc.client.library.event.channel.ChannelModeEvent
import org.kitteh.irc.client.library.event.channel.ChannelModeInfoListEvent
import org.kitteh.irc.client.library.event.channel.ChannelNoticeEvent
import org.kitteh.irc.client.library.event.channel.ChannelPartEvent
import org.kitteh.irc.client.library.event.channel.ChannelTagMessageEvent
import org.kitteh.irc.client.library.event.channel.ChannelTopicEvent
import org.kitteh.irc.client.library.event.client.ClientNegotiationCompleteEvent
import org.kitteh.irc.client.library.event.client.ClientReceiveNumericEvent
import org.kitteh.irc.client.library.event.connection.ClientConnectionClosedEvent
import org.kitteh.irc.client.library.event.helper.ServerMessageEvent
import org.kitteh.irc.client.library.event.user.PrivateCtcpQueryEvent
import org.kitteh.irc.client.library.event.user.PrivateMessageEvent
import org.kitteh.irc.client.library.event.user.PrivateNoticeEvent
import org.kitteh.irc.client.library.event.user.PrivateTagMessageEvent
import org.kitteh.irc.client.library.event.user.UserNickChangeEvent
import org.kitteh.irc.client.library.event.user.UserQuitEvent
import org.kitteh.irc.client.library.event.user.WhoisEvent
import org.kitteh.irc.client.library.feature.CapabilityManager
import org.kitteh.irc.client.library.feature.auth.NickServ
import org.kitteh.irc.client.library.feature.auth.SaslPlain
import org.kitteh.irc.client.library.feature.filter.NumericFilter
import org.kitteh.irc.client.library.feature.network.ProxyType
import timber.log.Timber

/**
 * IRC adapter (Kitteh / KICL) implementing [MessengerProtocol].
 *
 * Supports TLS or cleartext, SOCKS5 via Tor when [ProxyConfig.torRequired], NickServ / SASL PLAIN,
 * channel JOIN/PART/KICK/MODE/TOPIC, PRIVMSG (channel + DM), CTCP ACTION, WHOIS, IRCv3
 * server-time / echo-message / draft/chathistory / +draft/react, and NAMES contacts.
 * DCC is blocked (Tor-unsafe).
 */
@Singleton
class IrcProtocol @Inject constructor(
    private val networkGuard: NetworkGuard,
    private val repository: MessengerRepository,
) : MessengerProtocol {

    override val id: ProtocolId = ProtocolId.IRC

    override val capabilities = ProtocolCapabilities(
        directMessages = true,
        groupChats = true,
        mediaSend = false,
        mediaReceive = false,
        typingIndicators = false,
        readReceipts = false,
        endToEndEncryption = false,
        // NAMES list, not an address book — UI labels this "Nicks".
        contacts = true,
        profileEdit = false,
        messageHistory = false,
        backupExport = true,
        reactions = false,
        messageEdit = false,
        messageDelete = false,
        messageForward = false,
        messageReply = true,
        localChatListControls = true,
        voiceCalls = false,
        videoCalls = false,
        stories = false,
    )

    override fun capabilitiesFor(accountId: String?): ProtocolCapabilities {
        if (accountId == null) return capabilities
        val session = sessions[accountId]
        val connected = session?.connected == true
        val caps = session?.negotiatedCaps.orEmpty()
        return capabilities.copy(
            contacts = connected,
            messageHistory = connected && caps.any {
                it.equals("draft/chathistory", true) || it.equals("chathistory", true)
            },
            reactions = connected && caps.any { it.equals("message-tags", true) },
        )
    }

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = ConcurrentHashMap<String, IrcSession>()

    override fun isAccountConnected(accountId: String): Boolean =
        sessions[accountId]?.connected == true

    override suspend fun connect(account: AccountCredentials, proxy: ProxyConfig): ConnectionResult =
        withContext(Dispatchers.IO) {
            try {
                networkGuard.assertNetworkAllowed()

                val host = account.secrets["host"]?.trim().orEmpty()
                val nick = account.secrets["nick"]?.trim().orEmpty()
                if (host.isBlank()) {
                    refreshConnectionState()
                    return@withContext ConnectionResult.Failure("Hôte IRC manquant")
                }
                if (nick.isBlank()) {
                    refreshConnectionState()
                    return@withContext ConnectionResult.Failure("Pseudo IRC manquant")
                }

                val port = account.secrets["port"]?.toIntOrNull() ?: 6697
                val tlsSecret = account.secrets["tls"] ?: account.secrets["useTls"]
                val useTls = when {
                    tlsSecret != null -> !tlsSecret.equals("false", ignoreCase = true)
                    else -> port != 6667
                }
                val user = account.secrets["user"]?.trim()?.takeIf { it.isNotEmpty() } ?: nick
                val realName = account.secrets["realName"]?.trim()?.takeIf { it.isNotEmpty() }
                    ?: account.displayName.ifBlank { nick }
                val serverPassword = account.secrets["password"]?.takeIf { it.isNotBlank() }
                val nickServPassword = account.secrets["nickServPassword"]?.takeIf { it.isNotBlank() }
                val saslPassword = account.secrets["saslPassword"]?.takeIf { it.isNotBlank() }
                val saslRequested = account.secrets["sasl"]?.equals("true", ignoreCase = true) == true ||
                    saslPassword != null
                // SASL runs during registration (CAP). NickServ IDENTIFY runs after RPL_MYINFO (004).
                // A lone password with sasl=true is the SASL secret. Distinct secrets use both protocols.
                val saslCredential = when {
                    !saslRequested -> null
                    saslPassword != null -> saslPassword
                    else -> nickServPassword
                }
                val nickServCredential = when {
                    nickServPassword == null -> null
                    saslRequested && saslPassword == null -> null
                    else -> nickServPassword
                }
                val channelJoins = IrcTargets.mergeChannelKeys(
                    account.secrets["channels"],
                    account.secrets["channelKeys"],
                )

                if (!useTls &&
                    (saslCredential != null || nickServCredential != null || serverPassword != null)
                ) {
                    refreshConnectionState()
                    return@withContext ConnectionResult.Failure(
                        "IRC : mot de passe / SASL / NickServ exigent TLS (port 6697+)",
                    )
                }

                sessions.remove(account.accountId)?.shutdown()

                val ready = CompletableDeferred<Unit>()
                val fail = CompletableDeferred<Exception>()
                val listener = IrcEventListener(
                    account.accountId,
                    awaitSasl = saslCredential != null,
                    awaitNickServ = nickServCredential != null,
                    ready,
                    fail,
                )

                val builder = Client.builder()
                    .name("SecureMessenger-${account.accountId.take(8)}")
                    .nick(nick)
                    .user(user)
                    .realName(realName)
                builder.server()
                    .host(host)
                    .port(
                        port,
                        if (useTls) {
                            Client.Builder.Server.SecurityType.SECURE
                        } else {
                            Client.Builder.Server.SecurityType.INSECURE
                        },
                    )
                    .apply {
                        if (serverPassword != null) password(serverPassword)
                        // Required for Libera / Let's Encrypt and most public networks — Kitteh's
                        // built-in trust defaults reject popular CAs until a TMF is supplied.
                        if (useTls) secureTrustManagerFactory(IrcTls.systemTrustManagerFactory())
                    }
                    .then()
                builder.listeners()
                    .exception { e ->
                        Timber.w(e, "IRC exception for %s", account.accountId)
                        fail.complete(e)
                    }
                    .then()

                val socksHost = SocksEndpointResolver.resolveReachableHost(proxy.host, proxy.port)
                builder.proxy()
                    .proxyHost(socksHost)
                    .proxyPort(proxy.port)
                    .proxyType(ProxyType.SOCKS_5)
                    .then()

                val torNetwork = IrcTorNetworkHandler.create(proxy)
                // Kitteh 9 Client.Builder has no resolver(). NetworkHandler.setResolver is the hook
                // NettyNetworkHandler.connect uses; IrcTorBlockingResolver forces unresolved SOCKS DNS.
                torNetwork.setResolver(IrcTorBlockingResolver)
                builder.management()
                    .networkHandler(torNetwork)
                    .then()

                val client = builder.build()
                client.eventManager.registerEventListener(listener)

                if (saslCredential != null) {
                    client.authManager.addProtocol(SaslPlain(client, nick, saslCredential))
                }
                if (nickServCredential != null) {
                    client.authManager.addProtocol(
                        NickServ.builder(client)
                            .account(nick)
                            .password(nickServCredential)
                            .build(),
                    )
                }

                val session = IrcSession(
                    accountId = account.accountId,
                    displayName = account.displayName.ifBlank { nick },
                    requestedNick = nick,
                    client = client,
                    listener = listener,
                    autoJoin = channelJoins,
                )
                sessions[account.accountId] = session
                listener.bind(session)
                refreshConnectionState()

                client.connect()

                val connectedOk = withTimeoutOrNull(90.seconds) {
                    while (!ready.isCompleted && !fail.isCompleted) {
                        kotlinx.coroutines.delay(50)
                    }
                    when {
                        ready.isCompleted -> true
                        fail.isCompleted -> {
                            val err = runCatching { fail.getCompleted() }.getOrNull()
                            throw err ?: IllegalStateException("IRC connection failed")
                        }
                        else -> false
                    }
                }

                if (connectedOk != true) {
                    val cause = runCatching { fail.getCompleted() }.getOrNull()
                    val reason = cause?.let { formatTlsFailure(it) }
                        ?: "IRC connection timed out"
                    sessions.remove(account.accountId)?.shutdown()
                    refreshConnectionState(preferErrorIfEmpty = true)
                    return@withContext ConnectionResult.Failure(reason)
                }

                session.connected = true
                session.syncEffectiveNick()
                session.autoJoinChannels()

                repository.upsertAccount(
                    Account(
                        id = account.accountId,
                        protocol = ProtocolId.IRC,
                        displayName = account.displayName.ifBlank { nick },
                        connectionState = ConnectionState.CONNECTED,
                    ),
                )
                refreshConnectionState()
                ConnectionResult.Success
            } catch (e: Exception) {
                Timber.w(e, "IRC connect failed")
                sessions.remove(account.accountId)?.shutdown()
                refreshConnectionState(preferErrorIfEmpty = true)
                ConnectionResult.Failure(formatTlsFailure(e))
            }
        }

    private fun formatTlsFailure(e: Throwable): String {
        val chain = generateSequence(e) { it.cause }.toList()
        val ssl = chain.firstOrNull {
            it is javax.net.ssl.SSLException || it.javaClass.name.contains("SSL", ignoreCase = true)
        }
        return when {
            ssl != null ->
                "TLS échoué (${ssl.message ?: ssl.javaClass.simpleName}). " +
                    "Vérifiez hôte/port (Libera: irc.libera.chat:6697) et Tor si activé."
            else -> e.message ?: "IRC connection failed"
        }
    }

    override fun observeConversations(): Flow<List<Conversation>> =
        repository.observeConversations().map { list ->
            list.filter { it.protocol == ProtocolId.IRC }
        }

    override fun observeMessages(conversationId: String): Flow<List<Message>> =
        repository.observeMessages(conversationId)

    override fun observeContacts(accountId: String): Flow<List<Contact>> =
        repository.observeContacts(accountId)

    override suspend fun refreshContacts(accountId: String): Result<Int> =
        withContext(Dispatchers.IO) {
            val session = sessions[accountId]
                ?: return@withContext Result.failure(IllegalStateException("Non connecté"))
            val contacts = mutableListOf<Contact>()
            session.client.channels.forEach { channel ->
                channel.users.forEach { user ->
                    val nick = user.nick
                    if (session.isSelfNick(nick)) return@forEach
                    contacts += Contact(
                        id = "${accountId}_$nick",
                        protocol = ProtocolId.IRC,
                        accountId = accountId,
                        remoteId = nick,
                        displayName = nick,
                        handle = nick,
                    )
                }
            }
            val distinct = contacts.distinctBy { IrcTargets.foldNick(it.remoteId) }
            repository.replaceContacts(accountId, distinct)
            Result.success(distinct.size)
        }

    override suspend fun getAccountProfile(accountId: String): AccountProfile? {
        val session = sessions[accountId] ?: return null
        return AccountProfile(
            accountId = accountId,
            protocol = ProtocolId.IRC,
            displayName = session.displayName,
            handle = session.effectiveNick,
        )
    }

    override suspend fun startConversation(
        remoteId: String,
        initialMessage: SanitizedText?,
        accountId: String?,
        asGroup: Boolean,
    ): SendResult =
        withContext(Dispatchers.IO) {
            val accId = when {
                accountId != null -> accountId
                sessions.size == 1 -> sessions.keys.single()
                sessions.isEmpty() -> return@withContext SendResult.Failure("Non connecté")
                else -> return@withContext SendResult.Failure(
                    "Plusieurs comptes IRC — précise accountId",
                )
            }
            val session = sessions[accId]
                ?: return@withContext SendResult.Failure("Account not connected")
            networkGuard.assertNetworkAllowed()

            val target = remoteId.trim()
            if (target.isBlank()) return@withContext SendResult.Failure("Pseudo ou canal manquant")

            val channelish = asGroup || IrcTargets.isChannel(target)
            val remote = when {
                channelish -> IrcTargets.canonicalChannel(target)
                else -> IrcTargets.foldNick(target)
            }

            if (channelish) {
                runCatching { session.client.addChannel(remote) }
                    .onFailure { e ->
                        return@withContext SendResult.Failure(e.message ?: "JOIN failed")
                    }
            }

            val convId = IrcTargets.conversationId(accId, remote)
            repository.upsertConversation(
                Conversation(
                    id = convId,
                    protocol = ProtocolId.IRC,
                    accountId = accId,
                    remoteId = remote,
                    title = remote,
                ),
            )
            if (initialMessage != null) {
                val send = sendMessage(convId, initialMessage, accId)
                if (send is SendResult.Failure) return@withContext send
            }
            SendResult.Success(convId)
        }

    override suspend fun sendMessage(
        conversationId: String,
        body: SanitizedText,
        accountId: String?,
    ): SendResult =
        withContext(Dispatchers.IO) {
            try {
                networkGuard.assertNetworkAllowed()
                val accId = accountId ?: IrcTargets.accountIdFromConversation(conversationId)
                val session = sessions[accId]
                    ?: return@withContext SendResult.Failure("Account not connected")
                val remote = IrcTargets.canonicalRemote(
                    IrcTargets.remoteFromConversation(conversationId),
                    session.caseMapping,
                )
                val text = body.value
                if (text.isBlank()) return@withContext SendResult.Failure("Empty message")

                val slash = IrcSlashCommands.tryHandle(
                    client = session.client,
                    conversationRemote = remote,
                    effectiveNick = session.effectiveNick,
                    negotiatedCaps = session.negotiatedCaps,
                    text = text,
                )
                val displayBody: String
                val kind: MessageKind
                val awaitEcho: Boolean
                if (slash != null) {
                    displayBody = slash.displayBody
                    kind = slash.kind
                    awaitEcho = slash.awaitEcho
                } else {
                    session.client.sendMultiLineMessage(remote, text)
                    displayBody = text
                    kind = MessageKind.TEXT
                    awaitEcho = session.hasEchoMessage
                }

                val localId = "${conversationId}_${UUID.randomUUID()}"
                val now = System.currentTimeMillis()
                if (!awaitEcho) {
                    repository.upsertMessage(
                        Message(
                            id = localId,
                            conversationId = conversationId,
                            protocol = ProtocolId.IRC,
                            body = displayBody,
                            timestamp = now,
                            direction = MessageDirection.OUTGOING,
                            deliveryState = DeliveryState.SENT,
                            senderDisplayName = session.effectiveNick,
                            kind = kind,
                        ),
                    )
                    touchConversation(accId, remote, displayBody, now)
                }
                SendResult.Success(localId)
            } catch (e: Exception) {
                Timber.w(e, "IRC send failed")
                SendResult.Failure(e.message ?: "IRC send failed")
            }
        }

    override suspend fun loadMessageHistory(conversationId: String): HistoryLoadResult =
        withContext(Dispatchers.IO) {
            val accId = IrcTargets.accountIdFromConversation(conversationId)
            val session = sessions[accId]
                ?: return@withContext HistoryLoadResult.Failure("Non connecté")
            if (!session.hasChathistory) {
                return@withContext HistoryLoadResult.Success(
                    messageCount = 0,
                    loadedFromCache = true,
                    syncedFromNetwork = false,
                )
            }
            val remote = IrcTargets.canonicalRemote(
                IrcTargets.remoteFromConversation(conversationId),
                session.caseMapping,
            )
            runCatching {
                networkGuard.assertNetworkAllowed()
                session.client.sendRawLine("CHATHISTORY LATEST $remote * 50")
                HistoryLoadResult.Success(
                    messageCount = 0,
                    loadedFromCache = false,
                    syncedFromNetwork = true,
                )
            }.getOrElse {
                HistoryLoadResult.Failure(it.message ?: "CHATHISTORY failed")
            }
        }

    override suspend fun closeConversation(conversationId: String) {
        withContext(Dispatchers.IO) {
            val accId = IrcTargets.accountIdFromConversation(conversationId)
            val session = sessions[accId] ?: return@withContext
            val remote = IrcTargets.canonicalRemote(
                IrcTargets.remoteFromConversation(conversationId),
                session.caseMapping,
            )
            if (IrcTargets.isChannel(remote)) {
                runCatching {
                    session.client.removeChannel(remote, "SecureMessenger leave")
                }.onFailure { Timber.w(it, "IRC PART on close failed") }
            }
        }
    }

    override suspend fun exportBackup(accountId: String, destinationPath: String): BackupExportResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val (convs, messages) = repository.exportSnapshot(accountId)
                val json = buildString {
                    append("{\"protocol\":\"IRC\",\"accountId\":")
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
                BackupExportResult.Success(
                    uriOrPath = destinationPath,
                    messageCount = messages.size,
                    conversationCount = convs.size,
                )
            }.getOrElse {
                BackupExportResult.Failure(it.message ?: "Export échoué")
            }
        }

    override suspend fun wipeLocalData(accountId: String) = Unit

    override suspend fun disconnect(accountId: String?) {
        withContext(Dispatchers.IO) {
            val toClose = if (accountId != null) {
                sessions.remove(accountId)?.let { listOf(accountId to it) } ?: emptyList()
            } else {
                val all = sessions.entries.map { it.key to it.value }
                sessions.clear()
                all
            }
            toClose.forEach { (id, session) ->
                session.shutdown()
                runCatching {
                    repository.upsertAccount(
                        Account(
                            id = id,
                            protocol = ProtocolId.IRC,
                            displayName = session.displayName,
                            connectionState = ConnectionState.DISCONNECTED,
                        ),
                    )
                }
            }
            refreshConnectionState()
        }
    }

    override suspend fun markRead(conversationId: String, messageId: String?) {
        withContext(Dispatchers.IO) {
            val conversation = repository.getConversation(conversationId) ?: return@withContext
            if (conversation.unreadCount > 0) {
                repository.upsertConversation(conversation.copy(unreadCount = 0))
            }
        }
    }

    private fun refreshConnectionState(preferErrorIfEmpty: Boolean = false) {
        _connectionState.value = when {
            sessions.values.any { it.connected } -> ConnectionState.CONNECTED
            sessions.isNotEmpty() -> ConnectionState.CONNECTING
            preferErrorIfEmpty -> ConnectionState.ERROR
            else -> ConnectionState.DISCONNECTED
        }
    }

    private suspend fun touchConversation(
        accountId: String,
        remote: String,
        preview: String,
        at: Long,
        unreadBump: Boolean = false,
    ) {
        val canonical = IrcTargets.canonicalRemote(remote)
        val convId = IrcTargets.conversationId(accountId, canonical)
        val existing = repository.listConversationsForAccount(accountId)
            .firstOrNull { it.id == convId }
        repository.upsertConversation(
            Conversation(
                id = convId,
                protocol = ProtocolId.IRC,
                accountId = accountId,
                remoteId = canonical,
                title = existing?.title ?: canonical,
                lastMessagePreview = preview.take(160),
                lastMessageAt = at,
                unreadCount = if (unreadBump) (existing?.unreadCount ?: 0) + 1 else (existing?.unreadCount ?: 0),
            ),
        )
    }

    private suspend fun persistIncoming(
        accountId: String,
        remote: String,
        body: String,
        sender: String?,
        kind: MessageKind = MessageKind.TEXT,
        timestampMs: Long = System.currentTimeMillis(),
        payloadJson: String? = null,
        direction: MessageDirection = MessageDirection.INCOMING,
        unreadBump: Boolean = direction == MessageDirection.INCOMING,
    ) {
        val mapping = sessions[accountId]?.caseMapping ?: CaseMapping.RFC1459
        val canonical = IrcTargets.canonicalRemote(remote, mapping)
        val convId = IrcTargets.conversationId(accountId, canonical)
        repository.upsertMessage(
            Message(
                id = "${convId}_${UUID.randomUUID()}",
                conversationId = convId,
                protocol = ProtocolId.IRC,
                body = body,
                timestamp = timestampMs,
                direction = direction,
                deliveryState = if (direction == MessageDirection.OUTGOING) {
                    DeliveryState.SENT
                } else {
                    DeliveryState.DELIVERED
                },
                senderDisplayName = sender,
                kind = kind,
                payloadJson = payloadJson,
            ),
        )
        touchConversation(accountId, canonical, body, timestampMs, unreadBump = unreadBump)
    }

    private fun eventTimestamp(event: ServerMessageEvent): Long =
        event.getTag("time", MessageTag.Time::class.java)
            .map { it.time.toEpochMilli() }
            .orElse(System.currentTimeMillis())

    private fun tagPayload(event: ServerMessageEvent): String? {
        val msgid = event.getTag("msgid").map { it.value.orElse(null) }.orElse(null)
        val account = event.getTag("account").map { it.value.orElse(null) }.orElse(null)
        if (msgid.isNullOrBlank() && account.isNullOrBlank()) return null
        return JSONObject().apply {
            if (!msgid.isNullOrBlank()) put("msgid", msgid)
            if (!account.isNullOrBlank()) put("account", account)
        }.toString()
    }

    private fun reactPayload(emoji: String, msgid: String?, add: Boolean): String =
        JSONObject().apply {
            if (!msgid.isNullOrBlank()) put("msgid", msgid)
            val arr = JSONArray()
            if (add) arr.put(emoji)
            put("reactions", arr)
            put("reactAdd", add)
        }.toString()

    private inner class IrcEventListener(
        private val accountId: String,
        private val awaitSasl: Boolean,
        private val awaitNickServ: Boolean,
        private val ready: CompletableDeferred<Unit>,
        private val fail: CompletableDeferred<Exception>,
    ) {
        @Volatile
        private var saslDone: Boolean = !awaitSasl

        @Volatile
        private var nickServDone: Boolean = !awaitNickServ

        @Volatile
        private var negotiationComplete: Boolean = false
        @Volatile
        private var session: IrcSession? = null

        fun bind(s: IrcSession) {
            session = s
        }

        private fun tryMarkReady(source: String) {
            if (!negotiationComplete) return
            if (awaitSasl && !saslDone) return
            if (awaitNickServ && !nickServDone) return
            if (!ready.complete(Unit)) return
            session?.syncEffectiveNick()
            Timber.i("IRC ready (%s) for %s", source, accountId)
        }

        private fun markAuthFailure(reason: String) {
            if (ready.isCompleted) return
            fail.complete(IllegalStateException(reason))
        }

        @Handler
        fun onReady(event: ClientNegotiationCompleteEvent) {
            negotiationComplete = true
            val serverInfo = event.serverInfo
            val s = session
            if (s != null) {
                s.caseMapping = serverInfo.caseMapping
                s.syncEffectiveNick()
                s.refreshNegotiatedCaps()
                requestOptionalCaps(s)
            }
            Timber.i(
                "IRC negotiated with %s (CASEMAPPING=%s caps=%s)",
                event.server.name,
                serverInfo.caseMapping.name,
                s?.negotiatedCaps?.sorted()?.joinToString(",") ?: "",
            )
            if (awaitSasl && !saslDone) {
                markAuthFailure("Registration finished without SASL success")
                return
            }
            tryMarkReady("registration")
        }

        @Handler
        fun onCapsAck(event: CapabilitiesAcknowledgedEvent) {
            session?.refreshNegotiatedCaps()
            Timber.d(
                "IRC CAP ACK for %s: %s",
                accountId,
                event.acknowledgedCapabilities.joinToString { it.name },
            )
        }

        @Handler
        fun onCapRejected(event: CapabilitiesRejectedEvent) {
            if (!awaitSasl || ready.isCompleted) return
            val rejected = event.rejectedCapabilitiesRequest.map { it.name }
            if (rejected.any { it.equals("sasl", ignoreCase = true) }) {
                markAuthFailure("Server rejected SASL capability (CAP NAK)")
            }
        }

        @Handler
        @NumericFilter(903)
        fun onSaslSuccess(event: ClientReceiveNumericEvent) {
            if (!awaitSasl) return
            saslDone = true
            tryMarkReady("SASL 903")
        }

        @Handler
        fun onSaslFailure(event: ClientReceiveNumericEvent) {
            if (!awaitSasl || ready.isCompleted) return
            // Kitteh AbstractSaslProtocol.Listener: 902 and 904–908 abort SASL.
            when (event.numeric) {
                902, 904, 905, 906, 907, 908 ->
                    markAuthFailure("SASL authentication failed (${event.numeric})")
            }
        }

        private fun isNickServSuccess(notice: PrivateNoticeEvent): Boolean {
            val from = notice.actor.nick
            if (!from.equals("NickServ", ignoreCase = true)) return false
            return notice.message.startsWith("You are now identified", ignoreCase = true)
        }

        private fun isNickServFailure(notice: PrivateNoticeEvent): Boolean {
            val from = notice.actor.nick
            if (!from.equals("NickServ", ignoreCase = true)) return false
            val msg = notice.message.lowercase()
            return msg.contains("invalid password") ||
                msg.contains("authentication failed") ||
                msg.contains("isn't registered") ||
                msg.contains("is not registered")
        }

        @Handler
        fun onClosed(event: ClientConnectionClosedEvent) {
            val s = session ?: return
            s.connected = false
            if (!ready.isCompleted) {
                fail.complete(
                    IllegalStateException(event.lastMessage.orElse("IRC connection closed")),
                )
            }
            scope.launch {
                if (sessions[accountId] === s) {
                    sessions.remove(accountId, s)
                    s.shutdown()
                    repository.upsertAccount(
                        Account(
                            id = accountId,
                            protocol = ProtocolId.IRC,
                            displayName = s.displayName,
                            connectionState = ConnectionState.DISCONNECTED,
                        ),
                    )
                    refreshConnectionState()
                }
            }
        }

        @Handler
        fun onNickChange(event: UserNickChangeEvent) {
            val s = session ?: return
            if (s.isSelfNick(event.oldUser.nick)) {
                s.effectiveNick = event.newUser.nick
            }
        }

        @Handler
        fun onChannelMessage(event: ChannelMessageEvent) {
            val nick = event.actor.nick
            val self = session?.isSelfNick(nick) == true
            if (self && session?.hasEchoMessage != true) return
            val mapping = session?.caseMapping ?: CaseMapping.RFC1459
            val ts = eventTimestamp(event)
            val payload = tagPayload(event)
            scope.launch {
                persistIncoming(
                    accountId = accountId,
                    remote = IrcTargets.canonicalChannel(event.channel.messagingName, mapping),
                    body = event.message,
                    sender = nick,
                    timestampMs = ts,
                    payloadJson = payload,
                    direction = if (self) MessageDirection.OUTGOING else MessageDirection.INCOMING,
                    unreadBump = !self,
                )
            }
        }

        @Handler
        fun onPrivateMessage(event: PrivateMessageEvent) {
            val nick = event.actor.nick
            val self = session?.isSelfNick(nick) == true
            if (self && session?.hasEchoMessage != true) return
            val mapping = session?.caseMapping ?: CaseMapping.RFC1459
            val remote = if (self) {
                // Echo of our outbound DM — target is the peer, not ourselves.
                IrcTargets.foldNick(event.target, mapping)
            } else {
                IrcTargets.foldNick(nick, mapping)
            }
            scope.launch {
                persistIncoming(
                    accountId = accountId,
                    remote = remote,
                    body = event.message,
                    sender = nick,
                    timestampMs = eventTimestamp(event),
                    payloadJson = tagPayload(event),
                    direction = if (self) MessageDirection.OUTGOING else MessageDirection.INCOMING,
                    unreadBump = !self,
                )
            }
        }

        @Handler
        fun onChannelCtcp(event: ChannelCtcpEvent) {
            val msg = event.message
            if (!msg.startsWith("ACTION ", ignoreCase = true)) return
            val nick = event.actor.nick
            if (session?.isSelfNick(nick) == true && session?.hasEchoMessage != true) return
            val action = msg.removePrefix("ACTION ").removePrefix("action ")
            val mapping = session?.caseMapping ?: CaseMapping.RFC1459
            val self = session?.isSelfNick(nick) == true
            scope.launch {
                persistIncoming(
                    accountId = accountId,
                    remote = IrcTargets.canonicalChannel(event.channel.messagingName, mapping),
                    body = "* $nick $action",
                    sender = nick,
                    timestampMs = eventTimestamp(event),
                    payloadJson = tagPayload(event),
                    direction = if (self) MessageDirection.OUTGOING else MessageDirection.INCOMING,
                    unreadBump = !self,
                )
            }
        }

        @Handler
        fun onPrivateCtcp(event: PrivateCtcpQueryEvent) {
            val raw = event.message.trim()
            val type = raw.substringBefore(' ').uppercase()
            when (type) {
                "VERSION" -> event.setReply("VERSION SecureMessenger IRC (Kitteh/Tor)")
                "CLIENTINFO" -> event.setReply("CLIENTINFO VERSION PING TIME CLIENTINFO")
                "TIME" -> event.setReply("TIME ${java.time.Instant.now()}")
                "PING" -> {
                    val token = raw.substringAfter(' ', missingDelimiterValue = "")
                    if (token.isNotBlank()) event.setReply("PING $token")
                }
                "ACTION" -> {
                    val nick = event.actor.nick
                    if (session?.isSelfNick(nick) == true) return
                    val action = raw.removePrefix("ACTION ").removePrefix("action ")
                    val mapping = session?.caseMapping ?: CaseMapping.RFC1459
                    scope.launch {
                        persistIncoming(
                            accountId,
                            IrcTargets.foldNick(nick, mapping),
                            "* $nick $action",
                            nick,
                            timestampMs = eventTimestamp(event),
                        )
                    }
                }
                "DCC" -> {
                    // Refuse Tor-unsafe DCC; leave reply empty.
                    Timber.i("IRC DCC ignored from %s (blocked)", event.actor.nick)
                }
                else -> Unit
            }
        }

        @Handler
        fun onMode(event: ChannelModeEvent) {
            val modes = event.statusList.asString
            if (modes.isBlank()) return
            val actor = (event.actor as? org.kitteh.irc.client.library.element.User)?.nick
                ?: event.actor.name
            scope.launch {
                persistIncoming(
                    accountId,
                    IrcTargets.canonicalChannel(event.channel.messagingName),
                    "MODE ${event.channel.messagingName} $modes (par $actor)",
                    actor,
                    MessageKind.SYSTEM,
                    timestampMs = eventTimestamp(event),
                )
            }
        }

        @Handler
        fun onModeInfoList(event: ChannelModeInfoListEvent) {
            val mode = event.mode.char
            val lines = event.modeInfo.map { info ->
                val creator = info.creator.orElse("?")
                val whenAt = info.creationTime.map { it.toString() }.orElse("?")
                "${info.mask.asString()} (par $creator @ $whenAt)"
            }
            val body = if (lines.isEmpty()) {
                "Liste mode +$mode ${event.channel.messagingName}: (vide)"
            } else {
                "Liste mode +$mode ${event.channel.messagingName}:\n" + lines.joinToString("\n")
            }
            scope.launch {
                persistIncoming(
                    accountId,
                    IrcTargets.canonicalChannel(event.channel.messagingName),
                    body,
                    null,
                    MessageKind.SYSTEM,
                )
            }
        }

        @Handler
        fun onWhois(event: WhoisEvent) {
            val w = event.whoisData
            val body = buildString {
                append("WHOIS ${w.nick} (${w.userString}@${w.host})")
                w.realName.ifPresent { append(" — $it") }
                w.account.ifPresent { append(" account=$it") }
                w.server.ifPresent { append(" on $it") }
                w.serverDescription.ifPresent { append(" ($it)") }
                if (w.channels.isNotEmpty()) {
                    append(" chans=${w.channels.sorted().joinToString(",")}")
                }
                if (w.isAway) append(" [away]")
                if (w.isSecureConnection) append(" [secure]")
                w.idleTime.ifPresent { append(" idle=${it}s") }
            }
            scope.launch {
                persistIncoming(
                    accountId,
                    IrcTargets.foldNick(w.nick),
                    body,
                    w.nick,
                    MessageKind.SYSTEM,
                    unreadBump = false,
                )
            }
        }

        @Handler
        fun onQuit(event: UserQuitEvent) {
            val nick = event.user.nick
            if (session?.isSelfNick(nick) == true) return
            val reason = event.message.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
            val channelNames = buildList {
                event.affectedChannel.ifPresent { add(it.messagingName) }
                if (isEmpty()) addAll(event.user.channels)
            }
            if (channelNames.isEmpty()) return
            scope.launch {
                channelNames.distinct().forEach { ch ->
                    persistIncoming(
                        accountId,
                        IrcTargets.canonicalChannel(ch),
                        "* $nick a quitté le réseau$reason",
                        nick,
                        MessageKind.SYSTEM,
                        unreadBump = false,
                    )
                }
            }
        }

        @Handler
        fun onInvite(event: ChannelInviteEvent) {
            val target = event.target
            val s = session ?: return
            if (!s.isSelfNick(target) && !s.caseMapping.areEqualIgnoringCase(target, s.effectiveNick)) {
                // Invite-notify for others — still show in channel if we're there.
            }
            val actor = (event.actor as? org.kitteh.irc.client.library.element.User)?.nick
                ?: event.actor.name
            val channel = IrcTargets.canonicalChannel(event.channel.messagingName)
            scope.launch {
                val remote = if (s.isSelfNick(target)) {
                    // Store invite as a system note on the channel conversation.
                    channel
                } else {
                    channel
                }
                persistIncoming(
                    accountId,
                    remote,
                    "* $actor a invité $target sur $channel",
                    actor,
                    MessageKind.SYSTEM,
                )
            }
        }

        @Handler
        fun onChannelTag(event: ChannelTagMessageEvent) {
            handleReactTag(
                event,
                IrcTargets.canonicalChannel(event.channel.messagingName),
                (event.actor as? org.kitteh.irc.client.library.element.User)?.nick ?: event.actor.name,
            )
        }

        @Handler
        fun onPrivateTag(event: PrivateTagMessageEvent) {
            val nick = (event.actor as? org.kitteh.irc.client.library.element.User)?.nick
                ?: event.actor.name
            if (session?.isSelfNick(nick) == true) return
            handleReactTag(event, IrcTargets.foldNick(nick), nick)
        }

        private fun handleReactTag(event: ServerMessageEvent, remote: String, sender: String) {
            fun tagValue(vararg names: String): String? {
                for (name in names) {
                    val opt = event.getTag(name)
                    if (opt.isPresent) {
                        val v = opt.get().value.orElse(null)
                        if (!v.isNullOrBlank()) return v
                    }
                }
                return null
            }
            val react = tagValue("+draft/react", "draft/react")
            val unreact = tagValue("+draft/unreact", "draft/unreact")
            val emoji = react ?: unreact ?: return
            val msgid = tagValue("+reply", "reply")
            val add = react != null
            val label = if (add) "réagit" else "retire réaction"
            scope.launch {
                persistIncoming(
                    accountId = accountId,
                    remote = remote,
                    body = "* $sender $label $emoji" + (msgid?.let { " (msgid $it)" } ?: ""),
                    sender = sender,
                    kind = MessageKind.SYSTEM,
                    timestampMs = eventTimestamp(event),
                    payloadJson = reactPayload(
                        IrcSlashCommands.unescapeTag(emoji),
                        msgid?.let { IrcSlashCommands.unescapeTag(it) },
                        add,
                    ),
                    unreadBump = false,
                )
            }
        }

        @Handler
        fun onChannelNotice(event: ChannelNoticeEvent) {
            scope.launch {
                persistIncoming(
                    accountId,
                    IrcTargets.canonicalChannel(event.channel.messagingName),
                    "NOTICE ${event.actor.nick}: ${event.message}",
                    event.actor.nick,
                    MessageKind.SYSTEM,
                )
            }
        }

        @Handler
        fun onPrivateNotice(event: PrivateNoticeEvent) {
            val from = event.actor.nick
            if (awaitNickServ && !ready.isCompleted) {
                when {
                    isNickServSuccess(event) -> {
                        nickServDone = true
                        tryMarkReady("NickServ")
                    }
                    isNickServFailure(event) -> markAuthFailure("NickServ authentication failed")
                }
            }
            // NickServ chatter stays as a notice DM rather than flooding system channels.
            scope.launch {
                persistIncoming(
                    accountId,
                    IrcTargets.foldNick(from),
                    "NOTICE: ${event.message}",
                    from,
                    MessageKind.SYSTEM,
                )
            }
        }

        @Handler
        fun onJoin(event: ChannelJoinEvent) {
            val nick = event.user.nick
            val channel = IrcTargets.canonicalChannel(event.channel.messagingName)
            scope.launch {
                if (session?.isSelfNick(nick) == true) {
                    repository.upsertConversation(
                        Conversation(
                            id = IrcTargets.conversationId(accountId, channel),
                            protocol = ProtocolId.IRC,
                            accountId = accountId,
                            remoteId = channel,
                            title = channel,
                        ),
                    )
                }
                persistIncoming(
                    accountId,
                    channel,
                    "* $nick a rejoint $channel",
                    nick,
                    MessageKind.SYSTEM,
                )
                refreshContacts(accountId)
            }
        }

        @Handler
        fun onPart(event: ChannelPartEvent) {
            val partText = event.message.toString()
            val reason = if (partText.isNotBlank()) " ($partText)" else ""
            scope.launch {
                persistIncoming(
                    accountId,
                    IrcTargets.canonicalChannel(event.channel.messagingName),
                    "* ${event.user.nick} a quitté ${event.channel.messagingName}$reason",
                    event.user.nick,
                    MessageKind.SYSTEM,
                )
            }
        }

        @Handler
        fun onKick(event: ChannelKickEvent) {
            val kickText = event.message.toString()
            val reason = if (kickText.isNotBlank()) " ($kickText)" else ""
            val actor = event.actor
            val kicker = (actor as? org.kitteh.irc.client.library.element.User)?.nick ?: actor.name
            scope.launch {
                persistIncoming(
                    accountId,
                    IrcTargets.canonicalChannel(event.channel.messagingName),
                    "* ${event.target.nick} a été kické de ${event.channel.messagingName} par $kicker$reason",
                    kicker,
                    MessageKind.SYSTEM,
                )
            }
        }

        @Handler
        fun onTopic(event: ChannelTopicEvent) {
            val topic = event.newTopic.value.orElse("")
            if (topic.isBlank()) return
            scope.launch {
                persistIncoming(
                    accountId,
                    IrcTargets.canonicalChannel(event.channel.messagingName),
                    "Topic: $topic",
                    event.newTopic.setter.map { it.name }.orElse(null),
                    MessageKind.SYSTEM,
                )
            }
        }
    }

    private fun requestOptionalCaps(session: IrcSession) {
        val supported = session.client.capabilityManager.supportedCapabilities
            .map { it.name.lowercase() }
            .toSet()
        val enabled = session.negotiatedCaps
        val want = mutableListOf<String>()
        fun need(name: String) {
            if (supported.contains(name.lowercase()) && !enabled.contains(name.lowercase())) {
                want += name
            }
        }
        need("echo-message")
        need("invite-notify")
        when {
            "draft/chathistory" in supported -> need("draft/chathistory")
            "chathistory" in supported -> need("chathistory")
        }
        if (want.isEmpty()) return
        runCatching {
            val req = session.client.commands().capabilityRequest()
            want.forEach { req.enable(it) }
            req.execute()
        }.onFailure { Timber.w(it, "CAP REQ optional caps failed: %s", want) }
    }

    private class IrcSession(
        val accountId: String,
        val displayName: String,
        val requestedNick: String,
        val client: Client,
        val listener: IrcEventListener,
        val autoJoin: List<IrcTargets.ChannelJoin>,
        @Volatile var effectiveNick: String = requestedNick,
        @Volatile var connected: Boolean = false,
        @Volatile var caseMapping: CaseMapping = CaseMapping.RFC1459,
        @Volatile var negotiatedCaps: Set<String> = emptySet(),
    ) {
        val hasEchoMessage: Boolean
            get() = negotiatedCaps.any { it.equals(CapabilityManager.Defaults.ECHO_MESSAGE, true) }

        val hasChathistory: Boolean
            get() = negotiatedCaps.any {
                it.equals("draft/chathistory", true) || it.equals("chathistory", true)
            }

        fun refreshNegotiatedCaps() {
            negotiatedCaps = client.capabilityManager.capabilities
                .map { it.name.lowercase() }
                .toSet()
        }

        fun syncEffectiveNick() {
            val live = client.nick?.takeIf { it.isNotBlank() }
            if (live != null) {
                effectiveNick = live
            }
        }

        fun isSelfNick(other: String): Boolean =
            caseMapping.areEqualIgnoringCase(
                IrcTargets.normalizeNick(other),
                IrcTargets.normalizeNick(effectiveNick),
            )

        fun autoJoinChannels() {
            autoJoin.forEach { join ->
                runCatching {
                    val key = join.key
                    if (key != null) {
                        client.addKeyProtectedChannel(join.name, key)
                    } else {
                        client.addChannel(join.name)
                    }
                }.onFailure { Timber.w(it, "IRC auto-join failed for %s", join.name) }
            }
        }

        fun shutdown() {
            connected = false
            runCatching { client.eventManager.unregisterEventListener(listener) }
            runCatching { client.shutdown("SecureMessenger disconnect") }
        }
    }
}
