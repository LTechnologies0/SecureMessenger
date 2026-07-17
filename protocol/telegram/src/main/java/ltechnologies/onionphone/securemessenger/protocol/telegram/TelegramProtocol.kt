package ltechnologies.onionphone.securemessenger.protocol.telegram

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
import ltechnologies.onionphone.securemessenger.core.network.NetworkBlockedException
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

    /**
     * One [TelegramSession] per connected accountId. Connecting account B must never tear down
     * account A's live TDLib client.
     */
    internal val sessions = ConcurrentHashMap<String, TelegramSession>()

    private var authenticatingAccountId: String? = null

    fun tdLibFacade(accountId: String? = null): TdLibFacade? =
        accountId?.let { sessions[it]?.facade } ?: sessions.values.singleOrNull()?.facade

    override fun isAccountConnected(accountId: String): Boolean = sessions.containsKey(accountId)

    private fun session(accountId: String? = null): TelegramSession? =
        accountId?.let { sessions[it] } ?: sessions.values.singleOrNull()

    private fun sessionForConversation(conversationId: String): TelegramSession? {
        val accId = TdLibMapper.accountIdFromConversation(conversationId) ?: return null
        return sessions[accId]
    }

    private fun refreshConnectionState() {
        _connectionState.value = when {
            sessions.isEmpty() -> ConnectionState.DISCONNECTED
            sessions.values.any { it.awaitingAuth != AuthStepKind.NONE } -> ConnectionState.CONNECTING
            else -> ConnectionState.CONNECTED
        }
    }

    override suspend fun connect(account: AccountCredentials, proxy: ProxyConfig): ConnectionResult {
        val session = withContext(Dispatchers.IO) {
            try {
                networkGuard.assertNetworkAllowed()
                _connectionState.value = ConnectionState.CONNECTING
                authenticatingAccountId = account.accountId

                val apiId = account.secrets["apiId"]?.toIntOrNull()
                    ?: apiCredentials.apiId.takeIf { apiCredentials.isConfigured() }
                    ?: return@withContext null to ConnectionResult.Failure(
                        "Telegram non configuré (clés API développeur manquantes)",
                    )
                val apiHash = account.secrets["apiHash"]
                    ?: apiCredentials.apiHash.takeIf { apiCredentials.isConfigured() }
                    ?: return@withContext null to ConnectionResult.Failure(
                        "Telegram non configuré (clés API développeur manquantes)",
                    )
                val phone = account.secrets["phone"]
                    ?: return@withContext null to ConnectionResult.Failure("Missing phone number")

                tearDownSession(sessions.remove(account.accountId))

                val tdClient = TdLibClientFactory.create()
                val nativeAvailable = tdClient !is TdLibStubClient
                val tdFacade = TdLibFacade(tdClient)
                val newSession = TelegramSession(account.accountId, tdFacade).apply {
                    this.nativeAvailable = nativeAvailable
                    pendingPhone = phone
                    pendingApiId = apiId
                    pendingApiHash = apiHash
                    pendingDbDir = context.filesDir.resolve("tdlib_${account.accountId}").absolutePath
                    pendingProxy = proxy
                }

                tdClient.setUpdateHandler { update ->
                    tdFacade.onUpdate(update)
                    handleUpdate(account.accountId, update)
                }

                if (!nativeAvailable) {
                    newSession.close()
                    return@withContext null to ConnectionResult.Failure(
                        "libtdjni.so missing — build per docs/tdlib-build.md",
                    )
                }

                val proxyOk = withContext(newSession.dispatcher) {
                    tdFacade.configureProxy(proxy.host, proxy.port, proxy.username, proxy.password)
                }
                if (!proxyOk) {
                    newSession.close()
                    _connectionState.value = ConnectionState.ERROR
                    return@withContext null to ConnectionResult.Failure(
                        "Tor requis : démarrez Orbot ou InviZible pour Telegram",
                    )
                }

                sessions[account.accountId] = newSession
                refreshConnectionState()
                newSession to ConnectionResult.Success
            } catch (e: Exception) {
                Timber.w(e, "Telegram connect failed")
                _connectionState.value = ConnectionState.ERROR
                null to ConnectionResult.Failure(e.message ?: "Telegram connection failed")
            }
        }
        return session.second
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
            is TdApi.UpdateFile -> onFileUpdate(accId, update.file)
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
        val attachments = TdLibMapper.attachmentsFromContent(content, id)
        updateScope.launch {
            val existing = repository.observeMessages(convId).first().firstOrNull { it.id == id }
            if (existing != null) {
                repository.upsertMessage(existing.copy(body = body, attachments = attachments))
            }
        }
    }

    private fun onFileUpdate(accId: String, file: TdApi.File) {
        val session = sessions[accId] ?: return
        val target = session.fileDownloads[file.id] ?: return
        updateScope.launch {
            val convId = target.messageId.substringBeforeLast('_')
            val messages = repository.observeMessages(convId).first()
            val existing = messages.firstOrNull { it.id == target.messageId } ?: return@launch
            val updatedAttachments = existing.attachments.map { att ->
                if (att.id != target.attachmentId) {
                    att
                } else {
                    val local = file.local
                    val state = when {
                        local.isDownloadingCompleted && !local.path.isNullOrBlank() -> {
                            session.fileDownloads.remove(file.id)
                            AttachmentState.READY
                        }
                        local.isDownloadingActive -> AttachmentState.DOWNLOADING
                        else -> AttachmentState.PENDING
                    }
                    att.copy(
                        localPath = local.path?.takeIf { it.isNotBlank() },
                        sizeBytes = file.size,
                        state = state,
                    )
                }
            }
            repository.upsertMessage(existing.copy(attachments = updatedAttachments))
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
            scheduleAttachmentDownloads(accId, message)
        }
    }

    private fun scheduleAttachmentDownloads(accId: String, message: Message) {
        val session = sessions[accId] ?: return
        message.attachments.filter { it.state == AttachmentState.PENDING && it.remoteRef != null }
            .forEach { attachment ->
                val fileId = attachment.remoteRef?.toIntOrNull() ?: return@forEach
                session.fileDownloads[fileId] = FileDownloadTarget(message.id, attachment.id)
                updateScope.launch {
                    withContext(session.dispatcher) {
                        session.facade.downloadFile(fileId)
                    }
                }
            }
    }

    private fun onAuthorizationState(accId: String, state: TdApi.AuthorizationState) {
        val session = sessions[accId] ?: return
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                _lastAuthError.value = null
                val proxy = session.pendingProxy
                updateScope.launch {
                    val proxyOk = if (proxy != null) {
                        withContext(session.dispatcher) {
                            session.facade.configureProxy(
                                proxy.host,
                                proxy.port,
                                proxy.username,
                                proxy.password,
                            )
                        }
                    } else {
                        true
                    }
                    if (!proxyOk) {
                        _lastAuthError.value = "Tor requis : démarrez Orbot ou InviZible pour Telegram"
                        _connectionState.value = ConnectionState.ERROR
                        disconnect(accId)
                        return@launch
                    }
                    withContext(session.dispatcher) {
                        session.facade.setParameters(
                            session.pendingDbDir,
                            session.pendingApiId,
                            session.pendingApiHash,
                        )
                    }
                }
            }
            is TdApi.AuthorizationStateWaitPhoneNumber -> {
                session.pendingPhone?.let { session.facade.setPhoneNumber(it) }
            }
            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> {
                session.otherDeviceLink = state.link
                session.awaitingAuth = AuthStepKind.TELEGRAM_OTHER_DEVICE
                session.authPrompt = "Confirmez la connexion sur un autre appareil Telegram : ${state.link}"
                refreshConnectionState()
                emitPendingAuthStep(session)
            }
            is TdApi.AuthorizationStateWaitCode -> {
                session.awaitingAuth = AuthStepKind.TELEGRAM_SMS_CODE
                session.authPrompt = codeDeliveryHint(state.codeInfo)
                refreshConnectionState()
                emitPendingAuthStep(session)
            }
            is TdApi.AuthorizationStateWaitRegistration -> {
                session.awaitingAuth = AuthStepKind.TELEGRAM_REGISTRATION
                session.authPrompt = state.termsOfService?.text?.text
                    ?: "Créez votre profil Telegram (prénom et nom)"
                refreshConnectionState()
                emitPendingAuthStep(session)
            }
            is TdApi.AuthorizationStateWaitPassword -> {
                session.awaitingAuth = AuthStepKind.TELEGRAM_PASSWORD
                session.authPrompt = state.passwordHint?.takeIf { it.isNotBlank() }
                    ?: "Entrez votre mot de passe à deux facteurs"
                refreshConnectionState()
                emitPendingAuthStep(session)
            }
            is TdApi.AuthorizationStateReady -> {
                session.awaitingAuth = AuthStepKind.NONE
                _pendingAuthStep.value = null
                updateScope.launch { completeTelegramAuth(accId) }
            }
            is TdApi.AuthorizationStateClosed -> {
                session.awaitingAuth = AuthStepKind.NONE
                if (authenticatingAccountId == accId) {
                    _pendingAuthStep.value = null
                }
                tearDownSession(sessions.remove(accId))
                refreshConnectionState()
            }
            else -> Unit
        }
    }

    private fun emitPendingAuthStep(session: TelegramSession) {
        updateScope.launch { _pendingAuthStep.value = buildAuthStep(session) }
    }

    private fun buildAuthStep(session: TelegramSession): AuthStep? = when (session.awaitingAuth) {
        AuthStepKind.TELEGRAM_SMS_CODE -> AuthStep(
            kind = AuthStepKind.TELEGRAM_SMS_CODE,
            prompt = session.authPrompt.ifBlank { "Entrez le code reçu par SMS ou dans l'app Telegram" },
            fields = listOf("code"),
        )
        AuthStepKind.TELEGRAM_PASSWORD -> AuthStep(
            kind = AuthStepKind.TELEGRAM_PASSWORD,
            prompt = session.authPrompt.ifBlank { "Entrez votre mot de passe à deux facteurs" },
            fields = listOf("password"),
        )
        AuthStepKind.TELEGRAM_REGISTRATION -> AuthStep(
            kind = AuthStepKind.TELEGRAM_REGISTRATION,
            prompt = session.authPrompt,
            fields = listOf("firstName", "lastName"),
        )
        AuthStepKind.TELEGRAM_OTHER_DEVICE -> AuthStep(
            kind = AuthStepKind.TELEGRAM_OTHER_DEVICE,
            prompt = session.authPrompt,
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

    override suspend fun pendingAuthStep(): AuthStep? =
        session(authenticatingAccountId)?.let { buildAuthStep(it) }

    override suspend fun continueAuthentication(fields: Map<String, String>): ConnectionResult {
        val accId = authenticatingAccountId
        val session = session(accId) ?: return ConnectionResult.Failure("Not connected")
        return withContext(session.dispatcher) {
            try {
                networkGuard.assertNetworkAllowed()
                when (session.awaitingAuth) {
                    AuthStepKind.TELEGRAM_SMS_CODE -> {
                        val code = fields["code"] ?: return@withContext ConnectionResult.Failure("Missing code")
                        session.facade.checkCode(code)?.let { return@withContext ConnectionResult.Failure(it) }
                        ConnectionResult.Success
                    }
                    AuthStepKind.TELEGRAM_PASSWORD -> {
                        val password = fields["password"]
                            ?: return@withContext ConnectionResult.Failure("Missing password")
                        session.facade.checkPassword(password)?.let { return@withContext ConnectionResult.Failure(it) }
                        ConnectionResult.Success
                    }
                    AuthStepKind.TELEGRAM_REGISTRATION -> {
                        val first = fields["firstName"]?.trim().orEmpty()
                        val last = fields["lastName"]?.trim().orEmpty()
                        if (first.isBlank()) return@withContext ConnectionResult.Failure("Prénom requis")
                        session.facade.registerUser(first, last)?.let { return@withContext ConnectionResult.Failure(it) }
                        ConnectionResult.Success
                    }
                    else -> ConnectionResult.Success
                }
            } catch (e: NetworkBlockedException) {
                ConnectionResult.Failure(e.message ?: "Tor requis")
            }
        }
    }

    suspend fun resendAuthenticationCode(): ConnectionResult {
        val session = session(authenticatingAccountId) ?: return ConnectionResult.Failure("Not connected")
        return withContext(session.dispatcher) {
            try {
                networkGuard.assertNetworkAllowed()
                session.facade.resendCode()?.let { return@withContext ConnectionResult.Failure(it) }
                ConnectionResult.Success
            } catch (e: NetworkBlockedException) {
                ConnectionResult.Failure(e.message ?: "Tor requis")
            }
        }
    }

    private suspend fun completeTelegramAuth(accId: String): ConnectionResult {
        val session = sessions[accId] ?: return ConnectionResult.Failure("No account")
        session.awaitingAuth = AuthStepKind.NONE
        authenticatingAccountId = null
        syncChatList(accId, session)
        val me = withContext(session.dispatcher) { session.facade.getMe() }
        val displayName = me?.let { "${it.firstName} ${it.lastName}".trim().ifBlank { null } }
            ?: session.pendingPhone
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
        refreshConnectionState()
        return ConnectionResult.Success
    }

    private suspend fun syncChatList(accId: String, session: TelegramSession) {
        val chats = withContext(session.dispatcher) { session.facade.syncChatList(CHAT_SYNC_LIMIT) }
        repository.upsertConversations(chats.map { TdLibMapper.toConversation(accId, it) })
        Timber.i("Synced ${chats.size} Telegram chats for $accId")
    }

    override fun observeConversations(): Flow<List<Conversation>> = repository.observeConversations()

    override fun observeMessages(conversationId: String): Flow<List<Message>> =
        repository.observeMessages(conversationId)

    override suspend fun loadMessageHistory(conversationId: String): HistoryLoadResult {
        val accId = TdLibMapper.accountIdFromConversation(conversationId)
            ?: return HistoryLoadResult.Failure("Compte Telegram introuvable")
        val session = sessions[accId]
            ?: return HistoryLoadResult.Failure("Telegram non connecté")
        return withContext(session.dispatcher) {
            val chatId = TdLibMapper.chatIdFromConversation(conversationId)
                ?: return@withContext HistoryLoadResult.Failure("Conversation invalide")
            if (!session.nativeAvailable) {
                return@withContext HistoryLoadResult.Failure("TDLib indisponible sur cet appareil")
            }
            if (!isAccountConnected(accId)) {
                return@withContext HistoryLoadResult.Failure(
                    "Telegram déconnecté — vérifiez Tor (Orbot/InviZible) puis reconnectez-vous",
                )
            }

            session.openChatId = chatId
            session.facade.openChat(chatId)
            val chat = session.facade.getChat(chatId)
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
                    batch.forEach { scheduleAttachmentDownloads(accId, it) }
                }
            }

            val (localRaw, remoteRaw) = session.facade.fetchFullChatHistory(
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
                session.facade.viewMessages(chatId, longArrayOf(latestId))
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
    }

    override suspend fun closeConversation(conversationId: String) {
        val session = sessionForConversation(conversationId) ?: return
        val chatId = TdLibMapper.chatIdFromConversation(conversationId) ?: return
        withContext(session.dispatcher) {
            if (session.openChatId == chatId) {
                session.facade.closeChat(chatId)
                session.openChatId = null
            }
        }
    }

    override suspend fun startConversation(
        remoteId: String,
        initialMessage: SanitizedText?,
        accountId: String?,
    ): SendResult {
        val accId = accountId ?: sessions.keys.singleOrNull()
            ?: return SendResult.Failure("Not connected")
        val session = sessions[accId] ?: return SendResult.Failure("Not connected")

        val chat = withContext(session.dispatcher) {
            val asChatId = remoteId.toLongOrNull()
            if (asChatId != null) {
                session.facade.getChat(asChatId)
            } else {
                session.facade.searchPublicChat(remoteId)
            }
        } ?: return SendResult.Failure("Utilisateur ou chat introuvable : $remoteId")

        val convId = TdLibMapper.conversationId(accId, chat.id)
        repository.upsertConversation(TdLibMapper.toConversation(accId, chat))
        return if (initialMessage != null) sendMessage(convId, initialMessage, accId) else SendResult.Success(convId)
    }

    override suspend fun sendMessage(conversationId: String, body: SanitizedText, accountId: String?): SendResult {
        val accId = accountId
            ?: TdLibMapper.accountIdFromConversation(conversationId)
            ?: return SendResult.Failure("Invalid conversation")
        val session = sessions[accId] ?: return SendResult.Failure("Telegram not connected")
        if (!session.nativeAvailable) return SendResult.Failure("Telegram not connected")
        val chatId = TdLibMapper.chatIdFromConversation(conversationId)
            ?: return SendResult.Failure("Invalid conversation")
        return withContext(session.dispatcher) {
            try {
                networkGuard.assertNetworkAllowed()
                session.facade.sendText(chatId, body.value)
                SendResult.Success("pending")
            } catch (e: NetworkBlockedException) {
                SendResult.Failure(e.message ?: "Tor requis")
            }
        }
    }

    override suspend fun sendMedia(
        conversationId: String,
        attachment: Attachment,
        caption: SanitizedText?,
        accountId: String?,
    ): SendResult {
        val accId = accountId
            ?: TdLibMapper.accountIdFromConversation(conversationId)
            ?: return SendResult.Failure("Invalid conversation")
        val session = sessions[accId] ?: return SendResult.Failure("Telegram not connected")
        if (!session.nativeAvailable) return SendResult.Failure("Telegram not connected")
        val chatId = TdLibMapper.chatIdFromConversation(conversationId)
            ?: return SendResult.Failure("Invalid conversation")
        val localPath = attachment.localPath
            ?: return SendResult.Failure("Missing local file")
        return withContext(session.dispatcher) {
            try {
                networkGuard.assertNetworkAllowed()
                val error = session.facade.sendMedia(
                    chatId,
                    localPath,
                    attachment.mimeType,
                    caption?.value,
                )
                if (error != null) SendResult.Failure(error) else SendResult.Success("pending")
            } catch (e: NetworkBlockedException) {
                SendResult.Failure(e.message ?: "Tor requis")
            }
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
                tearDownSession(session)
                if (authenticatingAccountId == id) {
                    authenticatingAccountId = null
                    _pendingAuthStep.value = null
                }
                repository.upsertAccount(
                    ltechnologies.onionphone.securemessenger.core.model.Account(
                        id = id,
                        protocol = ProtocolId.TELEGRAM,
                        displayName = id,
                        connectionState = ConnectionState.DISCONNECTED,
                    ),
                )
            }
            refreshConnectionState()
        }
    }

    private fun tearDownSession(session: TelegramSession?) {
        session ?: return
        session.openChatId?.let { runCatching { session.facade.closeChat(it) } }
        session.openChatId = null
        runCatching { session.close() }
    }

    /** Re-applies Tor proxy to every live Telegram session (e.g. after proxy settings change). */
    suspend fun reapplyProxy(config: ProxyConfig) {
        sessions.values.forEach { session ->
            withContext(session.dispatcher) {
                session.facade.configureProxy(config.host, config.port, config.username, config.password)
            }
        }
    }

    companion object {
        private const val HISTORY_PAGE_SIZE = 100
        private const val CHAT_SYNC_LIMIT = 200

        fun conversationIdFor(accountId: String, chatId: Long) =
            TdLibMapper.conversationId(accountId, chatId)
    }
}
