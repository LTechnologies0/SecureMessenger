package ltechnologies.onionphone.securemessenger.protocol.xmpp

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ltechnologies.onionphone.securemessenger.core.model.AccountCredentials
import ltechnologies.onionphone.securemessenger.core.model.ConnectionResult
import ltechnologies.onionphone.securemessenger.core.model.ConnectionState
import ltechnologies.onionphone.securemessenger.core.model.Conversation
import ltechnologies.onionphone.securemessenger.core.model.DeliveryState
import ltechnologies.onionphone.securemessenger.core.model.Message
import ltechnologies.onionphone.securemessenger.core.model.MessageDirection
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
import org.jxmpp.jid.Jid
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
        mediaSend = false,
        mediaReceive = false,
        typingIndicators = true,
        readReceipts = false,
        endToEndEncryption = true,
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
    private val sessions = java.util.concurrent.ConcurrentHashMap<String, SmackClientFacade>()

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
        val entries = smack.rosterEntries()
        val conversations = entries.map { entry ->
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
        }
        repository.upsertConversations(conversations)
    }

    private suspend fun handleIncoming(
        accId: String,
        smack: SmackClientFacade,
        remoteJid: String,
        smackMessage: SmackMessage,
    ) {
        val omemoBody = smack.omemoHelper?.tryDecrypt(remoteJid, smackMessage)
        val body = omemoBody ?: smackMessage.body ?: return
        val convId = conversationId(accId, remoteJid)
        val ts = SmackClientFacade.extractDelayTimestamp(smackMessage) ?: System.currentTimeMillis()
        val myJid = smack.myBareJid()
        val outgoing = SmackClientFacade.isCarbonSent(smackMessage) ||
            smackMessage.from?.asBareJid()?.toString() == myJid
        val msg = Message(
            id = "${convId}_${smackMessage.stanzaId ?: ts}",
            conversationId = convId,
            protocol = ProtocolId.XMPP,
            body = body,
            timestamp = ts,
            direction = if (outgoing) MessageDirection.OUTGOING else MessageDirection.INCOMING,
            deliveryState = DeliveryState.DELIVERED,
            senderDisplayName = if (outgoing) myJid else remoteJid,
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
                unreadCount = 1,
            ),
        )
    }

    override fun observeConversations(): Flow<List<Conversation>> = repository.observeConversations()

    override fun observeMessages(conversationId: String): Flow<List<Message>> =
        repository.observeMessages(conversationId)

    override suspend fun startConversation(
        remoteId: String,
        initialMessage: SanitizedText?,
        accountId: String?,
    ): SendResult =
        withContext(Dispatchers.IO) {
            val accId = accountId ?: sessions.keys.singleOrNull()
                ?: return@withContext SendResult.Failure("Not connected")
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
                sendMessage(convId, initialMessage, accId)
            } else {
                SendResult.Success(convId)
            }
        }

    override suspend fun sendMessage(conversationId: String, body: SanitizedText, accountId: String?): SendResult =
        withContext(Dispatchers.IO) {
            try {
                networkGuard.assertNetworkAllowed()
                val accId = accountId ?: conversationId.substringBefore('_', missingDelimiterValue = conversationId)
                val smack = sessions[accId]
                    ?: return@withContext SendResult.Failure("Account not connected")
                val remoteJid = conversationId.substringAfter('_', missingDelimiterValue = conversationId)
                smack.sendChatMessage(remoteJid, body.value)

                val msg = Message(
                    id = "${conversationId}_${System.currentTimeMillis()}",
                    conversationId = conversationId,
                    protocol = ProtocolId.XMPP,
                    body = body.value,
                    timestamp = System.currentTimeMillis(),
                    direction = MessageDirection.OUTGOING,
                    deliveryState = DeliveryState.SENT,
                )
                repository.upsertMessage(msg)
                SendResult.Success(msg.id)
            } catch (e: SmackException.NotConnectedException) {
                SendResult.Failure("Not connected")
            } catch (e: Exception) {
                SendResult.Failure(e.message ?: "Send failed")
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
                repository.upsertAccount(
                    ltechnologies.onionphone.securemessenger.core.model.Account(
                        id = id,
                        protocol = ProtocolId.XMPP,
                        displayName = id,
                        connectionState = ConnectionState.DISCONNECTED,
                    ),
                )
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
