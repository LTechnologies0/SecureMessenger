package ltechnologies.onionphone.securemessenger.protocol.telegram

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.Executors
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
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import ltechnologies.onionphone.securemessenger.core.model.AccountCredentials
import ltechnologies.onionphone.securemessenger.core.model.AuthStep
import ltechnologies.onionphone.securemessenger.core.model.AuthStepKind
import ltechnologies.onionphone.securemessenger.core.model.ConnectionResult
import ltechnologies.onionphone.securemessenger.core.model.ConnectionState
import ltechnologies.onionphone.securemessenger.core.model.Conversation
import ltechnologies.onionphone.securemessenger.core.model.DeliveryState
import ltechnologies.onionphone.securemessenger.core.model.HistoryLoadResult
import ltechnologies.onionphone.securemessenger.core.model.Message
import ltechnologies.onionphone.securemessenger.core.model.MessageDirection
import ltechnologies.onionphone.securemessenger.core.model.ProtocolCapabilities
import ltechnologies.onionphone.securemessenger.core.model.ProtocolId
import ltechnologies.onionphone.securemessenger.core.model.ProxyConfig
import ltechnologies.onionphone.securemessenger.core.model.SanitizedText
import ltechnologies.onionphone.securemessenger.core.model.SendResult
import ltechnologies.onionphone.securemessenger.core.network.NetworkGuard
import ltechnologies.onionphone.securemessenger.data.MessengerRepository
import ltechnologies.onionphone.securemessenger.protocol.api.MessengerProtocol
import org.drinkless.tdlib.TdApi
import timber.log.Timber

/**
 * TDLib adapter implementing [MessengerProtocol].
 * Auth follows [TdApi.UpdateAuthorizationState]; chat/message sync uses the full TDLib update stream.
 */
@Singleton
class TelegramProtocol @Inject constructor(
    @ApplicationContext private val context: Context,
    private val networkGuard: NetworkGuard,
    private val repository: MessengerRepository,
    private val apiCredentials: TelegramApiCredentials,
) : MessengerProtocol {

    override val id: ProtocolId = ProtocolId.TELEGRAM

    override val capabilities = ProtocolCapabilities(
        directMessages = true,
        groupChats = true,
        mediaSend = true,
        mediaReceive = true,
        typingIndicators = true,
        readReceipts = true,
        endToEndEncryption = false,
        requiresPhoneAuth = true,
    )

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _pendingAuthStep = MutableStateFlow<AuthStep?>(null)
    fun observePendingAuthStep(): StateFlow<AuthStep?> = _pendingAuthStep.asStateFlow()

    private val _lastAuthError = MutableStateFlow<String?>(null)
    fun observeLastAuthError(): StateFlow<String?> = _lastAuthError.asStateFlow()

    private val updateScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tdDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "tdlib-client")
    }.asCoroutineDispatcher()

    private var facade: TdLibFacade? = null
    private var accountId: String? = null
    private var nativeAvailable = false
    private var awaitingAuth: AuthStepKind = AuthStepKind.NONE
    private var authPrompt: String = ""
    private var otherDeviceLink: String? = null
    private var openChatId: Long? = null

    private var pendingPhone: String? = null
    private var pendingApiId: Int = 0
    private var pendingApiHash: String = ""
    private var pendingDbDir: String = ""
    private var pendingProxy: ProxyConfig? = null

    fun tdLibFacade(): TdLibFacade? = facade

    override suspend fun connect(account: AccountCredentials, proxy: ProxyConfig): ConnectionResult =
        withContext(tdDispatcher) {
            try {
                networkGuard.assertNetworkAllowed()
                _connectionState.value = ConnectionState.CONNECTING

                val apiId = account.secrets["apiId"]?.toIntOrNull()
                    ?: apiCredentials.apiId.takeIf { apiCredentials.isConfigured() }
                    ?: return@withContext ConnectionResult.Failure(
                        "Telegram non configuré (clés API développeur manquantes)",
                    )
                val apiHash = account.secrets["apiHash"]
                    ?: apiCredentials.apiHash.takeIf { apiCredentials.isConfigured() }
                    ?: return@withContext ConnectionResult.Failure(
                        "Telegram non configuré (clés API développeur manquantes)",
                    )
                val phone = account.secrets["phone"]
                    ?: return@withContext ConnectionResult.Failure("Missing phone number")

                disconnect()
                accountId = account.accountId
                pendingPhone = phone
                pendingApiId = apiId
                pendingApiHash = apiHash
                pendingDbDir = context.filesDir.resolve("tdlib_${account.accountId}").absolutePath
                pendingProxy = proxy
                awaitingAuth = AuthStepKind.NONE
                authPrompt = ""
                otherDeviceLink = null
                openChatId = null

                val tdClient = TdLibClientFactory.create()
                nativeAvailable = tdClient !is TdLibStubClient
                val tdFacade = TdLibFacade(tdClient).also { f ->
                    tdClient.setUpdateHandler { update ->
                        f.onUpdate(update)
                        handleUpdate(account.accountId, update)
                    }
                }
                facade = tdFacade

                if (!nativeAvailable) {
                    _connectionState.value = ConnectionState.ERROR
                    return@withContext ConnectionResult.Failure(
                        "libtdjni.so missing — build per docs/tdlib-build.md",
                    )
                }

                tdFacade.configureProxy(proxy.host, proxy.port, proxy.username, proxy.password)
                    .let { ok ->
                        if (!ok) {
                            Timber.w("TDLib proxy setup failed for ${proxy.host}:${proxy.port}")
                        }
                    }
                _connectionState.value = ConnectionState.CONNECTING
                ConnectionResult.Success
            } catch (e: Exception) {
                Timber.w(e, "Telegram connect failed")
                _connectionState.value = ConnectionState.ERROR
                ConnectionResult.Failure(e.message ?: "Telegram connection failed")
            }
        }

    private fun handleUpdate(accId: String, update: TdApi.Object) {
        when (update) {
            is TdApi.UpdateAuthorizationState -> onAuthorizationState(accId, update.authorizationState)
            is TdApi.UpdateNewMessage -> onNewMessage(accId, update.message)
            is TdApi.UpdateNewChat -> onChat(accId, update.chat)
            is TdApi.UpdateChatLastMessage -> onChatLastMessage(accId, update)
            is TdApi.UpdateChatTitle -> onChatTitle(accId, update.chatId, update.title)
            is TdApi.UpdateChatReadInbox -> onChatReadInbox(accId, update.chatId, update.unreadCount)
            is TdApi.UpdateMessageSendSucceeded -> onMessageSendSucceeded(accId, update)
            is TdApi.UpdateMessageSendFailed -> onMessageSendFailed(accId, update)
            is TdApi.UpdateDeleteMessages -> onDeleteMessages(accId, update)
            is TdApi.UpdateMessageContent -> onMessageContent(accId, update.chatId, update.messageId, update.newContent)
            else -> Unit
        }
    }

    private fun onNewMessage(accId: String, msg: TdApi.Message) {
        val domain = TdLibMapper.toMessage(accId, msg)
        persistMessage(accId, domain, msg.chatId)
    }

    private fun onChat(accId: String, chat: TdApi.Chat) {
        updateScope.launch {
            repository.upsertConversation(TdLibMapper.toConversation(accId, chat))
        }
    }

    private fun onChatLastMessage(accId: String, update: TdApi.UpdateChatLastMessage) {
        val msg = update.lastMessage ?: return
        val domain = TdLibMapper.toMessage(accId, msg)
        updateScope.launch {
            val existing = repository.observeConversations().first()
                .firstOrNull { it.id == domain.conversationId }
            repository.upsertMessage(domain)
            repository.upsertConversation(
                Conversation(
                    id = domain.conversationId,
                    protocol = ProtocolId.TELEGRAM,
                    accountId = accId,
                    remoteId = update.chatId.toString(),
                    title = existing?.title ?: update.chatId.toString(),
                    lastMessagePreview = domain.body.take(100),
                    lastMessageAt = domain.timestamp,
                    unreadCount = existing?.unreadCount ?: 0,
                ),
            )
        }
    }

    private fun onChatTitle(accId: String, chatId: Long, title: String) {
        val convId = TdLibMapper.conversationId(accId, chatId)
        updateScope.launch {
            val existing = repository.observeConversations().first().firstOrNull { it.id == convId }
            if (existing != null) {
                repository.upsertConversation(existing.copy(title = title))
            }
        }
    }

    private fun onChatReadInbox(accId: String, chatId: Long, unreadCount: Int) {
        val convId = TdLibMapper.conversationId(accId, chatId)
        updateScope.launch {
            val existing = repository.observeConversations().first().firstOrNull { it.id == convId }
            if (existing != null) {
                repository.upsertConversation(existing.copy(unreadCount = unreadCount))
            }
        }
    }

    private fun onMessageSendSucceeded(accId: String, update: TdApi.UpdateMessageSendSucceeded) {
        val msg = update.message
        val convId = TdLibMapper.conversationId(accId, msg.chatId)
        val oldId = TdLibMapper.messageId(convId, update.oldMessageId)
        val newDomain = TdLibMapper.toMessage(accId, msg)
        updateScope.launch {
            repository.deleteMessages(listOf(oldId))
            repository.upsertMessage(newDomain)
        }
    }

    private fun onMessageSendFailed(accId: String, update: TdApi.UpdateMessageSendFailed) {
        val convId = TdLibMapper.conversationId(accId, update.message.chatId)
        val id = TdLibMapper.messageId(convId, update.oldMessageId)
        updateScope.launch {
            val existing = repository.observeMessages(convId).first().firstOrNull { it.id == id }
            if (existing != null) {
                repository.upsertMessage(existing.copy(deliveryState = DeliveryState.FAILED))
            }
        }
    }

    private fun onDeleteMessages(accId: String, update: TdApi.UpdateDeleteMessages) {
        val convId = TdLibMapper.conversationId(accId, update.chatId)
        val ids = update.messageIds.map { TdLibMapper.messageId(convId, it) }
        updateScope.launch { repository.deleteMessages(ids) }
    }

    private fun onMessageContent(
        accId: String,
        chatId: Long,
        messageId: Long,
        content: TdApi.MessageContent,
    ) {
        val body = TdLibMapper.messageBody(content)
        val convId = TdLibMapper.conversationId(accId, chatId)
        val id = TdLibMapper.messageId(convId, messageId)
        updateScope.launch {
            val existing = repository.observeMessages(convId).first().firstOrNull { it.id == id }
            if (existing != null) {
                repository.upsertMessage(existing.copy(body = body))
            }
        }
    }

    private fun persistMessage(accId: String, message: Message, chatId: Long) {
        updateScope.launch {
            val existing = repository.observeConversations().first()
                .firstOrNull { it.id == message.conversationId }
            repository.upsertMessage(message)
            repository.upsertConversation(
                Conversation(
                    id = message.conversationId,
                    protocol = ProtocolId.TELEGRAM,
                    accountId = accId,
                    remoteId = chatId.toString(),
                    title = existing?.title ?: chatId.toString(),
                    lastMessagePreview = message.body.take(100),
                    lastMessageAt = message.timestamp,
                    unreadCount = existing?.unreadCount ?: 0,
                ),
            )
        }
    }

    private fun onAuthorizationState(accId: String, state: TdApi.AuthorizationState) {
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                _lastAuthError.value = null
                val proxy = pendingProxy
                if (proxy != null) {
                    facade?.configureProxyFireAndForget(
                        proxy.host,
                        proxy.port,
                        proxy.username,
                        proxy.password,
                    )
                }
                facade?.setParameters(pendingDbDir, pendingApiId, pendingApiHash)
            }
            is TdApi.AuthorizationStateWaitPhoneNumber -> {
                pendingPhone?.let { facade?.setPhoneNumber(it) }
            }
            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> {
                otherDeviceLink = state.link
                awaitingAuth = AuthStepKind.TELEGRAM_OTHER_DEVICE
                authPrompt = "Confirmez la connexion sur un autre appareil Telegram : ${state.link}"
                _connectionState.value = ConnectionState.CONNECTING
                emitPendingAuthStep()
            }
            is TdApi.AuthorizationStateWaitCode -> {
                awaitingAuth = AuthStepKind.TELEGRAM_SMS_CODE
                authPrompt = codeDeliveryHint(state.codeInfo)
                _connectionState.value = ConnectionState.CONNECTING
                emitPendingAuthStep()
            }
            is TdApi.AuthorizationStateWaitRegistration -> {
                awaitingAuth = AuthStepKind.TELEGRAM_REGISTRATION
                authPrompt = state.termsOfService?.text?.text
                    ?: "Créez votre profil Telegram (prénom et nom)"
                _connectionState.value = ConnectionState.CONNECTING
                emitPendingAuthStep()
            }
            is TdApi.AuthorizationStateWaitPassword -> {
                awaitingAuth = AuthStepKind.TELEGRAM_PASSWORD
                authPrompt = state.passwordHint?.takeIf { it.isNotBlank() }
                    ?: "Entrez votre mot de passe à deux facteurs"
                _connectionState.value = ConnectionState.CONNECTING
                emitPendingAuthStep()
            }
            is TdApi.AuthorizationStateReady -> {
                awaitingAuth = AuthStepKind.NONE
                _pendingAuthStep.value = null
                updateScope.launch { completeTelegramAuth() }
            }
            is TdApi.AuthorizationStateClosed -> {
                awaitingAuth = AuthStepKind.NONE
                _pendingAuthStep.value = null
                _connectionState.value = ConnectionState.DISCONNECTED
            }
            else -> Unit
        }
    }

    private fun emitPendingAuthStep() {
        updateScope.launch { _pendingAuthStep.value = buildAuthStep() }
    }

    private fun buildAuthStep(): AuthStep? = when (awaitingAuth) {
        AuthStepKind.TELEGRAM_SMS_CODE -> AuthStep(
            kind = AuthStepKind.TELEGRAM_SMS_CODE,
            prompt = authPrompt.ifBlank { "Entrez le code reçu par SMS ou dans l'app Telegram" },
            fields = listOf("code"),
        )
        AuthStepKind.TELEGRAM_PASSWORD -> AuthStep(
            kind = AuthStepKind.TELEGRAM_PASSWORD,
            prompt = authPrompt.ifBlank { "Entrez votre mot de passe à deux facteurs" },
            fields = listOf("password"),
        )
        AuthStepKind.TELEGRAM_REGISTRATION -> AuthStep(
            kind = AuthStepKind.TELEGRAM_REGISTRATION,
            prompt = authPrompt,
            fields = listOf("firstName", "lastName"),
        )
        AuthStepKind.TELEGRAM_OTHER_DEVICE -> AuthStep(
            kind = AuthStepKind.TELEGRAM_OTHER_DEVICE,
            prompt = authPrompt,
            fields = emptyList(),
        )
        else -> null
    }

    private fun codeDeliveryHint(info: TdApi.AuthenticationCodeInfo): String = when (info.type) {
        is TdApi.AuthenticationCodeTypeTelegramMessage ->
            "Code envoyé dans l'app Telegram sur vos autres appareils"
        is TdApi.AuthenticationCodeTypeSms ->
            "Code envoyé par SMS au ${info.phoneNumber}"
        is TdApi.AuthenticationCodeTypeCall -> "Vous allez recevoir un appel avec le code"
        is TdApi.AuthenticationCodeTypeFlashCall ->
            "Vous allez recevoir un appel flash — entrez les derniers chiffres"
        is TdApi.AuthenticationCodeTypeMissedCall ->
            "Vous allez recevoir un appel manqué — entrez les derniers chiffres"
        is TdApi.AuthenticationCodeTypeFragment -> "Code envoyé via Fragment"
        else -> "Entrez le code reçu par SMS ou dans l'app Telegram"
    }

    override suspend fun pendingAuthStep(): AuthStep? = buildAuthStep()

    override suspend fun continueAuthentication(fields: Map<String, String>): ConnectionResult =
        withContext(tdDispatcher) {
            val tdFacade = facade ?: return@withContext ConnectionResult.Failure("Not connected")
            when (awaitingAuth) {
                AuthStepKind.TELEGRAM_SMS_CODE -> {
                    val code = fields["code"] ?: return@withContext ConnectionResult.Failure("Missing code")
                    tdFacade.checkCode(code)?.let { return@withContext ConnectionResult.Failure(it) }
                    ConnectionResult.Success
                }
                AuthStepKind.TELEGRAM_PASSWORD -> {
                    val password = fields["password"]
                        ?: return@withContext ConnectionResult.Failure("Missing password")
                    tdFacade.checkPassword(password)?.let { return@withContext ConnectionResult.Failure(it) }
                    ConnectionResult.Success
                }
                AuthStepKind.TELEGRAM_REGISTRATION -> {
                    val first = fields["firstName"]?.trim().orEmpty()
                    val last = fields["lastName"]?.trim().orEmpty()
                    if (first.isBlank()) return@withContext ConnectionResult.Failure("Prénom requis")
                    tdFacade.registerUser(first, last)?.let { return@withContext ConnectionResult.Failure(it) }
                    ConnectionResult.Success
                }
                else -> ConnectionResult.Success
            }
        }

    suspend fun resendAuthenticationCode(): ConnectionResult = withContext(tdDispatcher) {
        val tdFacade = facade ?: return@withContext ConnectionResult.Failure("Not connected")
        tdFacade.resendCode()?.let { return@withContext ConnectionResult.Failure(it) }
        ConnectionResult.Success
    }

    private suspend fun completeTelegramAuth(): ConnectionResult {
        val accId = accountId ?: return ConnectionResult.Failure("No account")
        awaitingAuth = AuthStepKind.NONE
        pendingProxy?.let { proxy ->
            facade?.configureProxy(proxy.host, proxy.port, proxy.username, proxy.password)
        }
        syncChatList(accId)
        val me = withContext(tdDispatcher) { facade?.getMe() }
        val displayName = me?.let { "${it.firstName} ${it.lastName}".trim().ifBlank { null } }
            ?: pendingPhone
            ?: repository.observeAccounts().first().firstOrNull { it.id == accId }?.displayName
            ?: accId
        repository.upsertAccount(
            ltechnologies.onionphone.securemessenger.core.model.Account(
                id = accId,
                protocol = ProtocolId.TELEGRAM,
                displayName = displayName,
                connectionState = ConnectionState.CONNECTED,
            ),
        )
        _connectionState.value = ConnectionState.CONNECTED
        return ConnectionResult.Success
    }

    private suspend fun syncChatList(accId: String) {
        val tdFacade = facade ?: return
        val chats = withContext(tdDispatcher) { tdFacade.syncChatList(CHAT_SYNC_LIMIT) }
        repository.upsertConversations(chats.map { TdLibMapper.toConversation(accId, it) })
        Timber.i("Synced ${chats.size} Telegram chats for $accId")
    }

    override fun observeConversations(): Flow<List<Conversation>> = repository.observeConversations()

    override fun observeMessages(conversationId: String): Flow<List<Message>> =
        repository.observeMessages(conversationId)

    override suspend fun loadMessageHistory(conversationId: String): HistoryLoadResult =
        withContext(tdDispatcher) {
            val chatId = TdLibMapper.chatIdFromConversation(conversationId)
                ?: return@withContext HistoryLoadResult.Failure("Conversation invalide")
            val accId = TdLibMapper.accountIdFromConversation(conversationId)
                ?: accountId
                ?: return@withContext HistoryLoadResult.Failure("Compte Telegram introuvable")
            if (accountId != accId) {
                return@withContext HistoryLoadResult.Failure("Telegram n'est pas connecté pour ce compte")
            }
            val tdFacade = facade
                ?: return@withContext HistoryLoadResult.Failure("Telegram non connecté")
            if (!nativeAvailable) {
                return@withContext HistoryLoadResult.Failure("TDLib indisponible sur cet appareil")
            }
            if (_connectionState.value != ConnectionState.CONNECTED) {
                return@withContext HistoryLoadResult.Failure(
                    "Telegram déconnecté — vérifiez Tor (Orbot/InviZible) puis reconnectez-vous",
                )
            }

            openChatId = chatId
            tdFacade.openChat(chatId)
            val chat = tdFacade.getChat(chatId)
            if (chat == null) {
                return@withContext HistoryLoadResult.Failure("Impossible d'ouvrir la conversation")
            }

            val syncRemote = runCatching { networkGuard.assertNetworkAllowed() }.isSuccess
            var persisted = 0
            suspend fun persistPage(page: List<TdApi.Message>) {
                val batch = page.map { TdLibMapper.toMessage(accId, it) }
                if (batch.isNotEmpty()) {
                    withContext(Dispatchers.IO) { repository.upsertMessages(batch) }
                    persisted += batch.size
                }
            }

            val (localRaw, remoteRaw) = tdFacade.fetchFullChatHistory(
                chatId = chatId,
                pageSize = HISTORY_PAGE_SIZE,
                syncRemote = syncRemote,
                onPage = ::persistPage,
            )
            val inDb = withContext(Dispatchers.IO) {
                repository.observeMessages(conversationId).first().size
            }
            Timber.i(
                "History chat=$chatId localRaw=$localRaw remoteRaw=$remoteRaw persisted=$persisted inDb=$inDb",
            )

            val latestId = withContext(Dispatchers.IO) {
                repository.observeMessages(conversationId).first()
                    .maxByOrNull { it.timestamp }
                    ?.id
                    ?.substringAfterLast('_')
                    ?.toLongOrNull()
            }
            if (latestId != null) {
                tdFacade.viewMessages(chatId, longArrayOf(latestId))
            }

            when {
                inDb > 0 -> HistoryLoadResult.Success(
                    messageCount = inDb,
                    loadedFromCache = localRaw > 0,
                    syncedFromNetwork = remoteRaw > 0,
                )
                !syncRemote -> HistoryLoadResult.Failure(
                    "Tor requis : démarrez Orbot ou InviZible pour charger l'historique",
                )
                remoteRaw == 0 -> HistoryLoadResult.Failure(
                    "Impossible de charger les messages — Tor ne répond pas ou la connexion Telegram a expiré",
                )
                else -> HistoryLoadResult.Success(
                    messageCount = 0,
                    loadedFromCache = false,
                    syncedFromNetwork = false,
                )
            }
        }

    override suspend fun closeConversation(conversationId: String) = withContext(tdDispatcher) {
        val chatId = TdLibMapper.chatIdFromConversation(conversationId) ?: return@withContext
        if (openChatId == chatId) {
            facade?.closeChat(chatId)
            openChatId = null
        }
    }

    override suspend fun startConversation(
        remoteId: String,
        initialMessage: SanitizedText?,
        accountId: String?,
    ): SendResult {
        // Telegram only supports a single connected account per app instance today; the
        // [accountId] parameter is accepted for interface symmetry with XMPP/Matrix but
        // intentionally ignored here in favor of the sole active TDLib session.
        val accId = this.accountId ?: return SendResult.Failure("Not connected")
        val tdFacade = facade ?: return SendResult.Failure("Not connected")

        val chat = withContext(tdDispatcher) {
            val asChatId = remoteId.toLongOrNull()
            if (asChatId != null) {
                tdFacade.getChat(asChatId)
            } else {
                tdFacade.searchPublicChat(remoteId)
            }
        } ?: return SendResult.Failure("Utilisateur ou chat introuvable : $remoteId")

        val convId = TdLibMapper.conversationId(accId, chat.id)
        repository.upsertConversation(TdLibMapper.toConversation(accId, chat))
        return if (initialMessage != null) sendMessage(convId, initialMessage) else SendResult.Success(convId)
    }

    override suspend fun sendMessage(conversationId: String, body: SanitizedText, accountId: String?): SendResult =
        withContext(tdDispatcher) {
            val tdFacade = facade
            if (tdFacade == null || !nativeAvailable) {
                return@withContext SendResult.Failure("Telegram not connected")
            }
            val chatId = TdLibMapper.chatIdFromConversation(conversationId)
                ?: return@withContext SendResult.Failure("Invalid conversation")
            tdFacade.sendText(chatId, body.value)
            SendResult.Success("pending")
        }

    override suspend fun disconnect(accountId: String?) {
        withContext(tdDispatcher) {
            openChatId?.let { facade?.closeChat(it) }
            openChatId = null
            try {
                facade?.close()
            } catch (_: Exception) {
            }
            facade = null
            awaitingAuth = AuthStepKind.NONE
            _pendingAuthStep.value = null
            pendingPhone = null
            this@TelegramProtocol.accountId = null
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    companion object {
        private const val HISTORY_PAGE_SIZE = 100
        private const val CHAT_SYNC_LIMIT = 200

        fun conversationIdFor(accountId: String, chatId: Long) =
            TdLibMapper.conversationId(accountId, chatId)
    }
}
