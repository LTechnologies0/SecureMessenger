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
import org.kitteh.irc.client.library.Client
import org.kitteh.irc.client.library.event.channel.ChannelJoinEvent
import org.kitteh.irc.client.library.event.channel.ChannelKickEvent
import org.kitteh.irc.client.library.event.channel.ChannelMessageEvent
import org.kitteh.irc.client.library.event.channel.ChannelNoticeEvent
import org.kitteh.irc.client.library.event.channel.ChannelPartEvent
import org.kitteh.irc.client.library.event.channel.ChannelTopicEvent
import org.kitteh.irc.client.library.event.client.ClientNegotiationCompleteEvent
import org.kitteh.irc.client.library.event.connection.ClientConnectionClosedEvent
import org.kitteh.irc.client.library.event.user.PrivateMessageEvent
import org.kitteh.irc.client.library.event.user.PrivateNoticeEvent
import org.kitteh.irc.client.library.feature.auth.NickServ
import org.kitteh.irc.client.library.feature.auth.SaslPlain
import org.kitteh.irc.client.library.feature.network.ProxyType
import timber.log.Timber

/**
 * IRC adapter (Kitteh / KICL) implementing [MessengerProtocol].
 *
 * Supports TLS or cleartext, SOCKS5 via Tor when [ProxyConfig.torRequired], NickServ / SASL PLAIN,
 * channel JOIN/PART, PRIVMSG (channel + DM), NOTICE/TOPIC/JOIN/PART/KICK as system messages,
 * and contact snapshots from channel NAMES lists.
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
        contacts = true,
        profileEdit = false,
        messageHistory = false,
        backupExport = true,
    )

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
                _connectionState.value = ConnectionState.CONNECTING

                val host = account.secrets["host"]?.trim().orEmpty()
                val nick = account.secrets["nick"]?.trim().orEmpty()
                if (host.isBlank()) return@withContext ConnectionResult.Failure("Missing IRC host")
                if (nick.isBlank()) return@withContext ConnectionResult.Failure("Missing IRC nick")

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
                val authPassword = account.secrets["nickServPassword"]
                    ?.takeIf { it.isNotBlank() }
                    ?: account.secrets["saslPassword"]?.takeIf { it.isNotBlank() }
                val sasl = account.secrets["sasl"]?.equals("true", ignoreCase = true) == true ||
                    account.secrets.containsKey("saslPassword")
                val channels = IrcTargets.parseChannels(account.secrets["channels"])

                sessions.remove(account.accountId)?.shutdown()

                val ready = CompletableDeferred<Unit>()
                val fail = CompletableDeferred<Exception>()
                val listener = IrcEventListener(account.accountId, ready, fail)

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
                    .apply { if (serverPassword != null) password(serverPassword) }
                    .then()
                builder.listeners()
                    .exception { e ->
                        Timber.w(e, "IRC exception for %s", account.accountId)
                        fail.complete(e)
                    }
                    .then()

                if (proxy.torRequired) {
                    val socksHost = SocksEndpointResolver.resolveReachableHost(proxy.host, proxy.port)
                    builder.proxy()
                        .proxyHost(socksHost)
                        .proxyPort(proxy.port)
                        .proxyType(ProxyType.SOCKS_5)
                        .then()
                }

                val client = builder.build()
                client.eventManager.registerEventListener(listener)

                if (!authPassword.isNullOrBlank()) {
                    if (sasl) {
                        client.authManager.addProtocol(SaslPlain(client, nick, authPassword))
                    } else {
                        client.authManager.addProtocol(
                            NickServ.builder(client)
                                .account(nick)
                                .password(authPassword)
                                .build(),
                        )
                    }
                }

                val session = IrcSession(
                    accountId = account.accountId,
                    displayName = account.displayName.ifBlank { nick },
                    nick = nick,
                    client = client,
                    listener = listener,
                    autoJoin = channels,
                )
                sessions[account.accountId] = session
                listener.bind(session)

                client.connect()

                client.connect()

                val connectedOk = withTimeoutOrNull(45.seconds) {
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
                    val reason = runCatching { fail.getCompleted().message }.getOrNull()
                        ?: "IRC connection timed out"
                    sessions.remove(account.accountId)?.shutdown()
                    if (sessions.isEmpty()) _connectionState.value = ConnectionState.ERROR
                    return@withContext ConnectionResult.Failure(reason)
                }

                session.connected = true
                channels.forEach { ch ->
                    runCatching { client.addChannel(ch) }
                        .onFailure { Timber.w(it, "IRC auto-join failed for %s", ch) }
                }

                repository.upsertAccount(
                    Account(
                        id = account.accountId,
                        protocol = ProtocolId.IRC,
                        displayName = account.displayName.ifBlank { nick },
                        connectionState = ConnectionState.CONNECTED,
                    ),
                )
                _connectionState.value = ConnectionState.CONNECTED
                ConnectionResult.Success
            } catch (e: Exception) {
                Timber.w(e, "IRC connect failed")
                sessions.remove(account.accountId)?.shutdown()
                if (sessions.isEmpty()) _connectionState.value = ConnectionState.ERROR
                ConnectionResult.Failure(e.message ?: "IRC connection failed")
            }
        }

    override fun observeConversations(): Flow<List<Conversation>> = repository.observeConversations()

    override fun observeMessages(conversationId: String): Flow<List<Message>> =
        repository.observeMessages(conversationId)

    override fun observeContacts(accountId: String): Flow<List<Contact>> =
        repository.observeContacts(accountId)

    override suspend fun refreshContacts(accountId: String): Result<Int> =
        withContext(Dispatchers.IO) {
            val session = sessions[accountId]
                ?: return@withContext Result.failure(IllegalStateException("Not connected"))
            val contacts = mutableListOf<Contact>()
            session.client.channels.forEach { channel ->
                channel.users.forEach { user ->
                    val nick = user.nick
                    if (nick.equals(session.nick, ignoreCase = true)) return@forEach
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
            val distinct = contacts.distinctBy { it.remoteId.lowercase() }
            repository.replaceContacts(accountId, distinct)
            Result.success(distinct.size)
        }

    override suspend fun getAccountProfile(accountId: String): AccountProfile? {
        val session = sessions[accountId] ?: return null
        return AccountProfile(
            accountId = accountId,
            protocol = ProtocolId.IRC,
            displayName = session.displayName,
            handle = session.client.nick,
        )
    }

    override suspend fun startConversation(
        remoteId: String,
        initialMessage: SanitizedText?,
        accountId: String?,
        asGroup: Boolean,
    ): SendResult =
        withContext(Dispatchers.IO) {
            val accId = accountId ?: sessions.keys.singleOrNull()
                ?: return@withContext SendResult.Failure("Not connected")
            val session = sessions[accId]
                ?: return@withContext SendResult.Failure("Account not connected")
            networkGuard.assertNetworkAllowed()

            val target = remoteId.trim()
            if (target.isBlank()) return@withContext SendResult.Failure("Missing nick or channel")

            val channelish = asGroup || IrcTargets.isChannel(target)
            val remote = when {
                channelish && !IrcTargets.isChannel(target) -> "#$target"
                else -> target
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
                val remote = IrcTargets.remoteFromConversation(conversationId)
                val text = body.value
                if (text.isBlank()) return@withContext SendResult.Failure("Empty message")

                session.client.sendMultiLineMessage(remote, text)

                val localId = "${conversationId}_${UUID.randomUUID()}"
                val now = System.currentTimeMillis()
                repository.upsertMessage(
                    Message(
                        id = localId,
                        conversationId = conversationId,
                        protocol = ProtocolId.IRC,
                        body = text,
                        timestamp = now,
                        direction = MessageDirection.OUTGOING,
                        deliveryState = DeliveryState.SENT,
                        senderDisplayName = session.client.nick,
                        kind = MessageKind.TEXT,
                    ),
                )
                touchConversation(accId, remote, text, now)
                SendResult.Success(localId)
            } catch (e: Exception) {
                Timber.w(e, "IRC send failed")
                SendResult.Failure(e.message ?: "IRC send failed")
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
            if (sessions.isEmpty()) {
                _connectionState.value = ConnectionState.DISCONNECTED
            } else if (sessions.values.any { it.connected }) {
                _connectionState.value = ConnectionState.CONNECTED
            }
        }
    }

    private suspend fun touchConversation(
        accountId: String,
        remote: String,
        preview: String,
        at: Long,
        unreadBump: Boolean = false,
    ) {
        val convId = IrcTargets.conversationId(accountId, remote)
        val existing = repository.listConversationsForAccount(accountId)
            .firstOrNull { it.id == convId }
        repository.upsertConversation(
            Conversation(
                id = convId,
                protocol = ProtocolId.IRC,
                accountId = accountId,
                remoteId = remote,
                title = existing?.title ?: remote,
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
    ) {
        val convId = IrcTargets.conversationId(accountId, remote)
        val now = System.currentTimeMillis()
        repository.upsertMessage(
            Message(
                id = "${convId}_${UUID.randomUUID()}",
                conversationId = convId,
                protocol = ProtocolId.IRC,
                body = body,
                timestamp = now,
                direction = MessageDirection.INCOMING,
                deliveryState = DeliveryState.DELIVERED,
                senderDisplayName = sender,
                kind = kind,
            ),
        )
        touchConversation(accountId, remote, body, now, unreadBump = true)
    }

    private inner class IrcEventListener(
        private val accountId: String,
        private val ready: CompletableDeferred<Unit>,
        private val fail: CompletableDeferred<Exception>,
    ) {
        @Volatile
        private var session: IrcSession? = null

        fun bind(s: IrcSession) {
            session = s
        }

        @Handler
        fun onReady(event: ClientNegotiationCompleteEvent) {
            ready.complete(Unit)
            Timber.i("IRC negotiated with %s", event.server.name)
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
                    repository.upsertAccount(
                        Account(
                            id = accountId,
                            protocol = ProtocolId.IRC,
                            displayName = s.displayName,
                            connectionState = ConnectionState.DISCONNECTED,
                        ),
                    )
                    if (sessions.values.none { it.connected }) {
                        _connectionState.value = ConnectionState.DISCONNECTED
                    }
                }
            }
        }

        @Handler
        fun onChannelMessage(event: ChannelMessageEvent) {
            val nick = event.actor.nick
            val myNick = session?.client?.nick
            if (myNick != null && nick.equals(myNick, ignoreCase = true)) return
            scope.launch {
                persistIncoming(accountId, event.channel.messagingName, event.message, nick)
            }
        }

        @Handler
        fun onPrivateMessage(event: PrivateMessageEvent) {
            val nick = event.actor.nick
            val myNick = session?.client?.nick
            if (myNick != null && nick.equals(myNick, ignoreCase = true)) return
            scope.launch {
                persistIncoming(accountId, nick, event.message, nick)
            }
        }

        @Handler
        fun onChannelNotice(event: ChannelNoticeEvent) {
            scope.launch {
                persistIncoming(
                    accountId,
                    event.channel.messagingName,
                    "NOTICE ${event.actor.nick}: ${event.message}",
                    event.actor.nick,
                    MessageKind.SYSTEM,
                )
            }
        }

        @Handler
        fun onPrivateNotice(event: PrivateNoticeEvent) {
            val from = event.actor.nick
            // NickServ chatter stays as a notice DM rather than flooding system channels.
            scope.launch {
                persistIncoming(
                    accountId,
                    from,
                    "NOTICE: ${event.message}",
                    from,
                    MessageKind.SYSTEM,
                )
            }
        }

        @Handler
        fun onJoin(event: ChannelJoinEvent) {
            val nick = event.user.nick
            val myNick = session?.client?.nick
            val channel = event.channel.messagingName
            scope.launch {
                if (myNick != null && nick.equals(myNick, ignoreCase = true)) {
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
                    event.channel.messagingName,
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
                    event.channel.messagingName,
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
                    event.channel.messagingName,
                    "Topic: $topic",
                    event.newTopic.setter.map { it.name }.orElse(null),
                    MessageKind.SYSTEM,
                )
            }
        }
    }

    private class IrcSession(
        val accountId: String,
        val displayName: String,
        val nick: String,
        val client: Client,
        val listener: IrcEventListener,
        val autoJoin: List<String>,
        @Volatile var connected: Boolean = false,
    ) {
        fun shutdown() {
            connected = false
            runCatching { client.eventManager.unregisterEventListener(listener) }
            runCatching { client.shutdown("SecureMessenger disconnect") }
        }
    }
}
