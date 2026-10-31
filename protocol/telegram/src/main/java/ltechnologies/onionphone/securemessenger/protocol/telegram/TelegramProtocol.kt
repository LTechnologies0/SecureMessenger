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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ltechnologies.onionphone.securemessenger.core.model.AccountCredentials
import ltechnologies.onionphone.securemessenger.core.model.AccountProfile
import ltechnologies.onionphone.securemessenger.core.model.Attachment
import ltechnologies.onionphone.securemessenger.core.model.AttachmentState
import ltechnologies.onionphone.securemessenger.core.model.AuthStep
import ltechnologies.onionphone.securemessenger.core.model.AuthStepKind
import ltechnologies.onionphone.securemessenger.core.model.BackupExportResult
import ltechnologies.onionphone.securemessenger.core.model.ConnectionResult
import ltechnologies.onionphone.securemessenger.core.model.ConnectionState
import ltechnologies.onionphone.securemessenger.core.model.Contact
import ltechnologies.onionphone.securemessenger.core.model.Conversation
import ltechnologies.onionphone.securemessenger.core.model.DeliveryState
import ltechnologies.onionphone.securemessenger.core.model.HistoryLoadResult
import ltechnologies.onionphone.securemessenger.core.model.Message
import ltechnologies.onionphone.securemessenger.core.model.MessageKind
import ltechnologies.onionphone.securemessenger.core.model.OutgoingContent
import ltechnologies.onionphone.securemessenger.core.model.ProtocolCapabilities
import ltechnologies.onionphone.securemessenger.core.model.ProtocolId
import ltechnologies.onionphone.securemessenger.core.model.ProxyConfig
import ltechnologies.onionphone.securemessenger.core.model.SanitizedText
import ltechnologies.onionphone.securemessenger.core.model.SendResult
import ltechnologies.onionphone.securemessenger.core.network.NetworkBlockedException
import ltechnologies.onionphone.securemessenger.core.network.NetworkGuard
import ltechnologies.onionphone.securemessenger.core.security.EncryptedCredentialStore
import ltechnologies.onionphone.securemessenger.data.MessengerRepository
import ltechnologies.onionphone.securemessenger.protocol.api.MessengerProtocol
import ltechnologies.onionphone.securemessenger.protocol.api.protocolIoScope
import org.drinkless.tdlib.TdApi
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

/** Sticker ready for the composer (local path after TDLib download). */
data class TelegramSticker(
    val id: Long,
    val emoji: String,
    val localPath: String?,
)

/**
 * TDLib adapter implementing [MessengerProtocol].
 * Auth follows [TdApi.UpdateAuthorizationState]; chat/message sync uses the full TDLib update stream.
 */
@Singleton
class TelegramProtocol @Inject constructor(
    @ApplicationContext private val context: Context,
    private val networkGuard: NetworkGuard,
    private val repository: MessengerRepository,
    private val credentialStore: EncryptedCredentialStore,
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
        contacts = true,
        profileEdit = true,
        voiceNotes = true,
        stickers = true,
        gifs = true,
        locationShare = true,
        polls = true,
        contactShare = true,
        // Chat auto-delete TTL is not per-message secret chat — hide ephemeral composer.
        ephemeralMessages = false,
        messageHistory = true,
        backupExport = true,
        reactions = true,
        messageEdit = true,
        messageDelete = true,
        messageForward = true,
        messageReply = true,
        localChatListControls = true,
        voiceCalls = false,
        videoCalls = false,
        stories = false,
    )

    override fun capabilitiesFor(accountId: String?): ProtocolCapabilities {
        if (accountId == null) return capabilities
        val session = sessions[accountId]
        if (session == null || !session.authorizationReady) {
            return capabilities.copy(
                mediaSend = false,
                mediaReceive = false,
                contacts = false,
                profileEdit = false,
                typingIndicators = false,
                readReceipts = false,
                voiceNotes = false,
                gifs = false,
                locationShare = false,
                polls = false,
                contactShare = false,
                stickers = false,
                messageHistory = false,
                reactions = false,
                messageEdit = false,
                messageDelete = false,
                messageForward = false,
                localChatListControls = false,
            )
        }
        return capabilities
    }

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /** Per-account pending auth — sibling SMS/2FA steps never overwrite each other. */
    private val _pendingAuthByAccount = MutableStateFlow<Map<String, AuthStep>>(emptyMap())
    fun observePendingAuthByAccount(): StateFlow<Map<String, AuthStep>> =
        _pendingAuthByAccount.asStateFlow()

    /** Preferred in-flight step (UI convenience); prefer [observePendingAuthByAccount] for multi-login. */
    private val _pendingAuthStep = MutableStateFlow<AuthStep?>(null)
    fun observePendingAuthStep(): StateFlow<AuthStep?> = _pendingAuthStep.asStateFlow()

    private val _lastAuthError = MutableStateFlow<String?>(null)
    fun observeLastAuthError(): StateFlow<String?> = _lastAuthError.asStateFlow()

    /** conversationId → display names of users currently typing. */
    private val typingFlows = ConcurrentHashMap<String, MutableStateFlow<List<String>>>()

    private val updateScope = protocolIoScope("telegram")

    /**
     * One [TelegramSession] per connected accountId. Connecting account B must never tear down
     * account A's live TDLib client.
     */
    internal val sessions = ConcurrentHashMap<String, TelegramSession>()

    private var authenticatingAccountId: String? = null

    fun tdLibFacade(accountId: String? = null): TdLibFacade? =
        when {
            accountId != null -> sessions[accountId]?.facade
            sessions.size == 1 -> sessions.values.single().facade
            else -> null
        }

    override fun isAccountConnected(accountId: String): Boolean =
        sessions[accountId]?.authorizationReady == true

    /** True while a TDLib client exists for [accountId] (including mid-auth). */
    fun hasLiveSession(accountId: String): Boolean = sessions.containsKey(accountId)

    private fun session(accountId: String? = null): TelegramSession? =
        when {
            accountId != null -> sessions[accountId]
            sessions.size == 1 -> sessions.values.single()
            else -> null
        }

    private fun sessionForConversation(conversationId: String): TelegramSession? {
        val accId = TdLibMapper.accountIdFromConversation(conversationId) ?: return null
        return sessions[accId]
    }

    private fun refreshConnectionState() {
        _connectionState.value = when {
            sessions.isEmpty() -> ConnectionState.DISCONNECTED
            sessions.values.any { it.awaitingAuth != AuthStepKind.NONE || !it.authorizationReady } ->
                ConnectionState.CONNECTING
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
                    _lastAuthError.value = null
                    return@withContext null to ConnectionResult.Failure(
                        "libtdjni.so missing — build per docs/tdlib-build.md",
                    )
                }

                sessions[account.accountId] = newSession
                refreshConnectionState()
                newSession to ConnectionResult.Success
            } catch (e: Exception) {
                Timber.w(e, "Telegram connect failed")
                refreshConnectionState()
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
            is TdApi.UpdateChatAction -> onChatAction(accId, update)
            is TdApi.UpdateChatPosition -> onChatPosition(accId, update)
            is TdApi.UpdateMessageInteractionInfo -> onMessageInteractionInfo(accId, update)
            is TdApi.UpdateChatFolders -> onChatFolders(accId, update)
            else -> Unit
        }
    }

    /**
     * Official TDLib clients drive main/archive membership from [TdApi.UpdateChatPosition].
     * order == 0 on ChatListMain means the chat left the main list (archive / delete / filter).
     */
    private fun onChatPosition(accId: String, update: TdApi.UpdateChatPosition) {
        val convId = TdLibMapper.conversationId(accId, update.chatId)
        updateScope.launch {
            val session = sessions[accId] ?: return@launch
            val chat = withContext(session.dispatcher) { session.facade.getChat(update.chatId) }
                ?: return@launch
            val positions = chat.positions?.toList().orEmpty()
            val visible = positions.any {
                (it.list is TdApi.ChatListMain || it.list is TdApi.ChatListArchive) && it.order > 0L
            }
            if (!visible) {
                runCatching { repository.deleteConversation(convId) }
                Timber.i("Telegram chat %s left all lists — removed conversation", update.chatId)
            } else {
                repository.upsertConversation(TdLibMapper.toConversation(accId, chat))
            }
        }
    }

    private fun onChatFolders(accId: String, update: TdApi.UpdateChatFolders) {
        val session = sessions[accId] ?: return
        session.chatFolders = update.chatFolders?.map { info ->
            TelegramChatFolder(
                id = info.id,
                title = info.name?.text?.text.orEmpty().ifBlank { "Folder ${info.id}" },
            )
        }.orEmpty()
    }

    private fun onMessageInteractionInfo(accId: String, update: TdApi.UpdateMessageInteractionInfo) {
        val convId = TdLibMapper.conversationId(accId, update.chatId)
        val msgKey = TdLibMapper.messageId(convId, update.messageId)
        updateScope.launch {
            val existing = repository.getMessage(msgKey) ?: return@launch
            val json = runCatching {
                JSONObject(existing.payloadJson ?: "{}")
            }.getOrElse { JSONObject() }
            TdLibMapper.mergeReactions(json, update.interactionInfo)
            repository.upsertMessage(existing.copy(payloadJson = json.toString()))
        }
    }

    private fun onChatAction(accId: String, update: TdApi.UpdateChatAction) {
        val convId = TdLibMapper.conversationId(accId, update.chatId)
        val flow = typingFlows.getOrPut(convId) { MutableStateFlow(emptyList()) }
        val sender = update.senderId
        val userId = (sender as? TdApi.MessageSenderUser)?.userId ?: return
        val isTyping = update.action is TdApi.ChatActionTyping ||
            update.action is TdApi.ChatActionRecordingVideo ||
            update.action is TdApi.ChatActionRecordingVoiceNote ||
            update.action is TdApi.ChatActionUploadingPhoto ||
            update.action is TdApi.ChatActionUploadingVideo ||
            update.action is TdApi.ChatActionUploadingVoiceNote ||
            update.action is TdApi.ChatActionUploadingDocument
        updateScope.launch {
            val label = withContext(sessions[accId]?.dispatcher ?: Dispatchers.IO) {
                sessions[accId]?.facade?.getUser(userId)?.let { u ->
                    "${u.firstName} ${u.lastName}".trim().ifBlank {
                        u.usernames?.editableUsername ?: userId.toString()
                    }
                } ?: userId.toString()
            }
            val current = flow.value.toMutableList()
            if (isTyping) {
                if (label !in current) current.add(label)
            } else {
                current.removeAll { it == label || it == userId.toString() }
            }
            flow.value = current
        }
    }

    override fun observeTyping(conversationId: String): StateFlow<List<String>> =
        typingFlows.getOrPut(conversationId) { MutableStateFlow(emptyList()) }.asStateFlow()

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
            val existing = repository.getConversation(domain.conversationId)
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
            val existing = repository.getConversation(convId)
            if (existing != null) {
                repository.upsertConversation(existing.copy(title = title))
            }
        }
    }

    private fun onChatReadInbox(accId: String, chatId: Long, unreadCount: Int) {
        val convId = TdLibMapper.conversationId(accId, chatId)
        updateScope.launch {
            val existing = repository.getConversation(convId)
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
            val existing = repository.getMessage(id)
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
        val kind = TdLibMapper.messageKind(content)
        val payload = TdLibMapper.payloadJson(content)
        updateScope.launch {
            val existing = repository.getMessage(id)
            if (existing != null) {
                repository.upsertMessage(
                    existing.copy(
                        body = body,
                        attachments = attachments,
                        kind = kind,
                        payloadJson = payload,
                    ),
                )
            }
        }
    }

    private fun onFileUpdate(accId: String, file: TdApi.File) {
        val session = sessions[accId] ?: return
        val target = session.fileDownloads[file.id] ?: return
        updateScope.launch {
            val existing = repository.getMessage(target.messageId) ?: return@launch
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

    private suspend fun finalizeTdSend(
        accId: String,
        chatId: Long,
        result: TdSendResult,
        optimisticPlaceholderId: String? = null,
    ): SendResult =
        when (result) {
            is TdSendResult.Err -> {
                optimisticPlaceholderId?.let { pid ->
                    repository.getMessage(pid)?.let { m ->
                        repository.upsertMessage(m.copy(deliveryState = DeliveryState.FAILED))
                    }
                }
                SendResult.Failure(result.reason)
            }
            is TdSendResult.Ok -> {
                optimisticPlaceholderId?.let { repository.deleteMessages(listOf(it)) }
                val domain = TdLibMapper.toMessage(accId, result.message)
                    .copy(deliveryState = DeliveryState.PENDING)
                persistMessage(accId, domain, chatId)
                SendResult.Success(domain.id)
            }
        }

    private fun persistMessage(accId: String, message: Message, chatId: Long) {
        updateScope.launch {
            val existing = repository.getConversation(message.conversationId)
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
                    // TDLib opens the network inside SetTdlibParameters. SOCKS must already
                    // be enabled (GetProxies / AddProxy) or the first packets leave Tor.
                    if (proxy == null || !proxy.torRequired) {
                        _lastAuthError.value =
                            "Tor requis : démarrez OnionVPN pour Telegram"
                        disconnect(accId)
                        refreshConnectionState()
                        return@launch
                    }
                    val proxyOk = withContext(session.dispatcher) {
                        session.facade.configureProxy(
                            proxy.host,
                            proxy.port,
                            proxy.username,
                            proxy.password,
                        )
                    }
                    if (!proxyOk) {
                        _lastAuthError.value =
                            "Tor requis : démarrez OnionVPN pour Telegram"
                        disconnect(accId)
                        refreshConnectionState()
                        return@launch
                    }
                    // Keystore-backed via EncryptedSharedPreferences (MasterKey) — never ephemeral.
                    val key = credentialStore.getOrCreateTdlibKey(accId)
                    val paramError = withContext(session.dispatcher) {
                        session.facade.setParameters(
                            session.pendingDbDir,
                            session.pendingApiId,
                            session.pendingApiHash,
                            key,
                        )
                    }
                    if (paramError != null) {
                        _lastAuthError.value = paramError
                        disconnect(accId)
                        refreshConnectionState()
                    }
                }
            }
            is TdApi.AuthorizationStateWaitPhoneNumber -> {
                session.pendingPhone?.let { session.facade.setPhoneNumber(it) }
            }
            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> {
                session.authorizationReady = false
                session.otherDeviceLink = state.link
                session.awaitingAuth = AuthStepKind.TELEGRAM_OTHER_DEVICE
                session.authPrompt = "Confirmez la connexion sur un autre appareil Telegram : ${state.link}"
                refreshConnectionState()
                emitPendingAuthStep(session)
            }
            is TdApi.AuthorizationStateWaitCode -> {
                session.authorizationReady = false
                session.awaitingAuth = AuthStepKind.TELEGRAM_SMS_CODE
                session.authPrompt = codeDeliveryHint(state.codeInfo)
                refreshConnectionState()
                emitPendingAuthStep(session)
            }
            is TdApi.AuthorizationStateWaitRegistration -> {
                session.authorizationReady = false
                session.awaitingAuth = AuthStepKind.TELEGRAM_REGISTRATION
                session.authPrompt = state.termsOfService?.text?.text
                    ?: "Créez votre profil Telegram (prénom et nom)"
                refreshConnectionState()
                emitPendingAuthStep(session)
            }
            is TdApi.AuthorizationStateWaitPassword -> {
                session.authorizationReady = false
                session.awaitingAuth = AuthStepKind.TELEGRAM_PASSWORD
                session.authPrompt = state.passwordHint?.takeIf { it.isNotBlank() }
                    ?: "Entrez votre mot de passe à deux facteurs"
                refreshConnectionState()
                emitPendingAuthStep(session)
            }
            is TdApi.AuthorizationStateWaitEmailAddress -> {
                session.authorizationReady = false
                session.awaitingAuth = AuthStepKind.TELEGRAM_EMAIL_ADDRESS
                session.authPrompt = buildString {
                    append("Telegram demande une adresse e-mail pour finaliser la connexion")
                    if (state.allowAppleId || state.allowGoogleId) {
                        append(" (Apple/Google ID non supportés ici — utilisez l'e-mail)")
                    }
                }
                refreshConnectionState()
                emitPendingAuthStep(session)
            }
            is TdApi.AuthorizationStateWaitEmailCode -> {
                session.authorizationReady = false
                session.awaitingAuth = AuthStepKind.TELEGRAM_EMAIL_CODE
                val pattern = state.codeInfo?.emailAddressPattern?.takeIf { it.isNotBlank() }
                session.authPrompt = if (pattern != null) {
                    "Entrez le code envoyé à $pattern"
                } else {
                    "Entrez le code reçu par e-mail"
                }
                refreshConnectionState()
                emitPendingAuthStep(session)
            }
            is TdApi.AuthorizationStateReady -> {
                session.awaitingAuth = AuthStepKind.NONE
                session.authorizationReady = true
                if (authenticatingAccountId == accId) {
                    authenticatingAccountId = null
                }
                republishPendingAuthStep()
                updateScope.launch { completeTelegramAuth(accId) }
            }
            is TdApi.AuthorizationStateClosed -> {
                session.awaitingAuth = AuthStepKind.NONE
                if (authenticatingAccountId == accId) {
                    authenticatingAccountId = null
                }
                tearDownSession(sessions.remove(accId))
                republishPendingAuthStep()
                refreshConnectionState()
            }
            else -> Unit
        }
    }

    private fun emitPendingAuthStep(session: TelegramSession) {
        authenticatingAccountId = session.accountId
        updateScope.launch {
            val step = buildAuthStep(session) ?: return@launch
            _pendingAuthByAccount.value = _pendingAuthByAccount.value + (session.accountId to step)
            _pendingAuthStep.value = step
        }
    }

    /** Keep another in-flight Telegram login visible when one account finishes or closes. */
    private fun republishPendingAuthStep() {
        val rebuilt = sessions.values
            .mapNotNull { session ->
                if (session.awaitingAuth == AuthStepKind.NONE) null
                else buildAuthStep(session)?.let { session.accountId to it }
            }
            .toMap()
        _pendingAuthByAccount.value = rebuilt
        val preferred = authenticatingAccountId?.let { rebuilt[it] }
        val next = preferred ?: rebuilt.values.firstOrNull()
        if (next == null) {
            _pendingAuthStep.value = null
            authenticatingAccountId = null
            return
        }
        authenticatingAccountId = next.accountId
        _pendingAuthStep.value = next
    }

    private fun buildAuthStep(session: TelegramSession): AuthStep? = when (session.awaitingAuth) {
        AuthStepKind.TELEGRAM_SMS_CODE -> AuthStep(
            kind = AuthStepKind.TELEGRAM_SMS_CODE,
            prompt = session.authPrompt.ifBlank { "Entrez le code reçu par SMS ou dans l'app Telegram" },
            fields = listOf("code"),
            accountId = session.accountId,
        )
        AuthStepKind.TELEGRAM_PASSWORD -> AuthStep(
            kind = AuthStepKind.TELEGRAM_PASSWORD,
            prompt = session.authPrompt.ifBlank { "Entrez votre mot de passe à deux facteurs" },
            fields = listOf("password"),
            accountId = session.accountId,
        )
        AuthStepKind.TELEGRAM_REGISTRATION -> AuthStep(
            kind = AuthStepKind.TELEGRAM_REGISTRATION,
            prompt = session.authPrompt,
            fields = listOf("firstName", "lastName"),
            accountId = session.accountId,
        )
        AuthStepKind.TELEGRAM_OTHER_DEVICE -> AuthStep(
            kind = AuthStepKind.TELEGRAM_OTHER_DEVICE,
            prompt = session.authPrompt,
            fields = emptyList(),
            accountId = session.accountId,
        )
        AuthStepKind.TELEGRAM_EMAIL_ADDRESS -> AuthStep(
            kind = AuthStepKind.TELEGRAM_EMAIL_ADDRESS,
            prompt = session.authPrompt.ifBlank { "Adresse e-mail requise par Telegram" },
            fields = listOf("email"),
            accountId = session.accountId,
        )
        AuthStepKind.TELEGRAM_EMAIL_CODE -> AuthStep(
            kind = AuthStepKind.TELEGRAM_EMAIL_CODE,
            prompt = session.authPrompt.ifBlank { "Entrez le code reçu par e-mail" },
            fields = listOf("emailCode"),
            accountId = session.accountId,
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

    override suspend fun pendingAuthStep(): AuthStep? {
        val preferred = authenticatingAccountId?.let { sessions[it] }
        if (preferred != null && preferred.awaitingAuth != AuthStepKind.NONE) {
            return buildAuthStep(preferred)
        }
        val awaiting = sessions.values.filter { it.awaitingAuth != AuthStepKind.NONE }
        val next = awaiting.firstOrNull() ?: return null
        authenticatingAccountId = next.accountId
        return buildAuthStep(next)
    }

    override suspend fun continueAuthentication(fields: Map<String, String>): ConnectionResult {
        val accId = fields["accountId"]?.takeIf { it.isNotBlank() }
            ?: authenticatingAccountId
        val session = session(accId) ?: return ConnectionResult.Failure("Not connected")
        if (accId != null) authenticatingAccountId = accId
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
                    AuthStepKind.TELEGRAM_EMAIL_ADDRESS -> {
                        val email = fields["email"]?.trim().orEmpty()
                        if (email.isBlank() || !email.contains('@')) {
                            return@withContext ConnectionResult.Failure("Adresse e-mail invalide")
                        }
                        session.facade.setAuthenticationEmailAddress(email)
                            ?.let { return@withContext ConnectionResult.Failure(it) }
                        ConnectionResult.Success
                    }
                    AuthStepKind.TELEGRAM_EMAIL_CODE -> {
                        val code = fields["emailCode"]?.trim().orEmpty().ifBlank {
                            fields["code"]?.trim().orEmpty()
                        }
                        if (code.isBlank()) return@withContext ConnectionResult.Failure("Code e-mail requis")
                        session.facade.checkAuthenticationEmailCode(code)
                            ?.let { return@withContext ConnectionResult.Failure(it) }
                        ConnectionResult.Success
                    }
                    else -> ConnectionResult.Success
                }
            } catch (e: NetworkBlockedException) {
                ConnectionResult.Failure(e.message ?: "Réseau indisponible")
            }
        }
    }

    suspend fun resendAuthenticationCode(accountId: String? = null): ConnectionResult {
        val accId = accountId?.takeIf { it.isNotBlank() } ?: authenticatingAccountId
        val session = session(accId) ?: return ConnectionResult.Failure("Not connected")
        if (accId != null) authenticatingAccountId = accId
        return withContext(session.dispatcher) {
            try {
                networkGuard.assertNetworkAllowed()
                session.facade.resendCode()?.let { return@withContext ConnectionResult.Failure(it) }
                ConnectionResult.Success
            } catch (e: NetworkBlockedException) {
                ConnectionResult.Failure(e.message ?: "Réseau indisponible")
            }
        }
    }

    private suspend fun completeTelegramAuth(accId: String): ConnectionResult {
        val session = sessions[accId] ?: return ConnectionResult.Failure("No account")
        session.awaitingAuth = AuthStepKind.NONE
        if (authenticatingAccountId == accId) {
            authenticatingAccountId = null
        }
        republishPendingAuthStep()
        syncChatList(accId, session)
        runCatching { refreshContacts(accId) }
            .onFailure { Timber.w(it, "Telegram contacts refresh after auth failed") }
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

    override fun observeConversations(): Flow<List<Conversation>> =
        repository.observeConversations().map { list ->
            list.filter { it.protocol == ProtocolId.TELEGRAM }
        }

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
                    "Telegram déconnecté — vérifiez Tor (OnionVPN) puis reconnectez-vous",
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
                maxPages = HISTORY_MAX_PAGES,
                syncRemote = syncRemote,
                onPage = ::persistPage,
            )
            val inDb = withContext(Dispatchers.IO) {
                repository.countMessages(conversationId)
            }
            Timber.i(
                "History chat=$chatId localRaw=$localRaw remoteRaw=$remoteRaw persisted=$persisted inDb=$inDb",
            )

            val latestId = withContext(Dispatchers.IO) {
                repository.latestMessageId(conversationId)
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
                    "Impossible de charger l'historique (réseau / Tor optionnel indisponible)",
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
        asGroup: Boolean,
    ): SendResult {
        val accId = when {
            accountId != null -> accountId
            sessions.size == 1 -> sessions.keys.single()
            sessions.isEmpty() -> return SendResult.Failure("Not connected")
            else -> return SendResult.Failure("Plusieurs comptes Telegram — précise accountId")
        }
        val session = sessions[accId] ?: return SendResult.Failure("Not connected")
        val trimmed = remoteId.trim()

        if (asGroup) {
            val createParts = parseTelegramGroupCreate(trimmed)
            if (createParts != null) {
                val (title, userIds) = createParts
                val created = withContext(session.dispatcher) {
                    session.facade.createNewBasicGroupChat(title, userIds)
                } ?: return SendResult.Failure(
                    "Impossible de créer le groupe Telegram « $title »",
                )
                val convId = TdLibMapper.conversationId(accId, created.id)
                repository.upsertConversation(TdLibMapper.toConversation(accId, created))
                return if (initialMessage != null) {
                    when (val send = sendMessage(convId, initialMessage, accId)) {
                        is SendResult.Failure -> send
                        else -> SendResult.Success(convId)
                    }
                } else {
                    SendResult.Success(convId)
                }
            }
            // Open existing group/channel — do not fail immediately.
            val chat = withContext(session.dispatcher) {
                val asChatId = trimmed.toLongOrNull()
                if (asChatId != null) {
                    session.facade.getChat(asChatId)
                } else {
                    session.facade.searchPublicChat(trimmed)
                }
            } ?: return SendResult.Failure(
                "Groupe Telegram introuvable : $trimmed " +
                    "(chat ID / @canal, ou Titre|userId1,userId2 pour créer)",
            )
            val convId = TdLibMapper.conversationId(accId, chat.id)
            repository.upsertConversation(TdLibMapper.toConversation(accId, chat))
            return if (initialMessage != null) {
                when (val send = sendMessage(convId, initialMessage, accId)) {
                    is SendResult.Failure -> send
                    else -> SendResult.Success(convId)
                }
            } else {
                SendResult.Success(convId)
            }
        }

        val chat = withContext(session.dispatcher) {
            val asChatId = trimmed.toLongOrNull()
            when {
                asChatId != null -> {
                    session.facade.getChat(asChatId)
                        ?: session.facade.createPrivateChat(asChatId)
                }
                trimmed.startsWith("+") || trimmed.all { it.isDigit() || it == '+' } -> {
                    val user = session.facade.searchUserByPhoneNumber(trimmed)
                    user?.let { session.facade.createPrivateChat(it.id) }
                }
                else -> session.facade.searchPublicChat(trimmed)
            }
        } ?: return SendResult.Failure(
            "Utilisateur ou chat introuvable : $trimmed " +
                "(chat ID, @username, userId, ou téléphone +33…)",
        )

        val convId = TdLibMapper.conversationId(accId, chat.id)
        repository.upsertConversation(TdLibMapper.toConversation(accId, chat))
        return if (initialMessage != null) {
            when (val send = sendMessage(convId, initialMessage, accId)) {
                is SendResult.Failure -> send
                else -> SendResult.Success(convId)
            }
        } else {
            SendResult.Success(convId)
        }
    }

    /** Parses `Title|+userId1,+userId2` / `Title|123,456` (positive Telegram user ids). */
    private fun parseTelegramGroupCreate(remoteId: String): Pair<String, LongArray>? {
        val sep = remoteId.indexOf('|')
        if (sep <= 0) return null
        val title = remoteId.substring(0, sep).trim()
        if (title.isEmpty()) return null
        val ids = remoteId.substring(sep + 1)
            .split(',')
            .map { it.trim().removePrefix("+") }
            .filter { it.isNotEmpty() }
            .mapNotNull { it.toLongOrNull()?.takeIf { id -> id > 0L } }
        if (ids.isEmpty()) return null
        return title to ids.toLongArray()
    }

    override suspend fun sendMessage(conversationId: String, body: SanitizedText, accountId: String?): SendResult {
        val accId = accountId
            ?: TdLibMapper.accountIdFromConversation(conversationId)
            ?: return SendResult.Failure("Invalid conversation")
        val session = sessions[accId] ?: return SendResult.Failure("Telegram not connected")
        if (!session.nativeAvailable) return SendResult.Failure("Telegram not connected")
        val chatId = TdLibMapper.chatIdFromConversation(conversationId)
            ?: return SendResult.Failure("Invalid conversation")
        val localId = "tg_out_${accId}_${System.currentTimeMillis()}"
        repository.upsertMessage(
            Message(
                id = localId,
                conversationId = conversationId,
                protocol = ProtocolId.TELEGRAM,
                body = body.value,
                timestamp = System.currentTimeMillis(),
                direction = ltechnologies.onionphone.securemessenger.core.model.MessageDirection.OUTGOING,
                deliveryState = DeliveryState.PENDING,
            ),
        )
        return withContext(session.dispatcher) {
            try {
                networkGuard.assertNetworkAllowed()
                finalizeTdSend(
                    accId,
                    chatId,
                    session.facade.sendTextMessage(chatId, body.value),
                    optimisticPlaceholderId = localId,
                )
            } catch (e: NetworkBlockedException) {
                repository.getMessage(localId)?.let { m ->
                    repository.upsertMessage(m.copy(deliveryState = DeliveryState.FAILED))
                }
                SendResult.Failure(e.message ?: "Réseau indisponible")
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
                finalizeTdSend(
                    accId,
                    chatId,
                    session.facade.sendMedia(
                        chatId,
                        localPath,
                        attachment.mimeType,
                        caption?.value,
                    ),
                )
            } catch (e: NetworkBlockedException) {
                SendResult.Failure(e.message ?: "Réseau indisponible")
            }
        }
    }

    override suspend fun sendContent(
        conversationId: String,
        content: OutgoingContent,
        accountId: String?,
    ): SendResult {
        when (content) {
            is OutgoingContent.Text -> return sendMessage(conversationId, content.body, accountId)
            is OutgoingContent.Media -> {
                if (content.kind == MessageKind.GIF) {
                    val path = content.attachment.localPath
                        ?: return SendResult.Failure("Missing local file")
                    return sendViaFacade(conversationId, accountId) { facade, chatId ->
                        facade.sendAnimation(chatId, path, content.caption?.value)
                    }
                }
                return sendMedia(conversationId, content.attachment, content.caption, accountId)
            }
            is OutgoingContent.VoiceNote -> {
                val path = content.attachment.localPath
                    ?: return SendResult.Failure("Missing local file")
                return sendViaFacade(conversationId, accountId) { facade, chatId ->
                    facade.sendVoiceNote(chatId, path, content.durationMs)
                }
            }
            is OutgoingContent.Location -> return sendViaFacade(conversationId, accountId) { facade, chatId ->
                facade.sendLocation(
                    chatId,
                    content.latitude,
                    content.longitude,
                    content.horizontalAccuracy,
                    content.livePeriodSec,
                )
            }
            is OutgoingContent.ContactCard -> return sendViaFacade(conversationId, accountId) { facade, chatId ->
                facade.sendContact(
                    chatId,
                    content.firstName,
                    content.lastName,
                    content.phone,
                    content.userId ?: 0L,
                )
            }
            is OutgoingContent.Poll -> {
                if (content.options.size < 2) {
                    return SendResult.Failure("Un sondage nécessite au moins 2 options")
                }
                return sendViaFacade(conversationId, accountId) { facade, chatId ->
                    facade.sendPoll(
                        chatId,
                        content.question,
                        content.options,
                        content.anonymous,
                        content.multipleAnswers,
                    )
                }
            }
            is OutgoingContent.Sticker -> return sendViaFacade(conversationId, accountId) { facade, chatId ->
                facade.sendSticker(chatId, content.localPath, content.emoji)
            }
            is OutgoingContent.Ephemeral -> {
                // Capability ephemeralMessages=false: not per-message self-destruct (secret chats
                // out of scope). Chat auto-delete exists in TDLib but is not advertised in UI.
                if (!capabilities.ephemeralMessages) {
                    return SendResult.Failure(
                        "Messages éphémères Telegram non exposés (pas de secret chats / TTL UI)",
                    )
                }
                if (content.expireSeconds <= 0) {
                    return SendResult.Failure("Durée d'expiration invalide")
                }
                return sendViaFacade(conversationId, accountId) { facade, chatId ->
                    val ttlError = facade.setChatMessageAutoDeleteTime(chatId, content.expireSeconds)
                    when {
                        ttlError != null -> TdSendResult.Err(ttlError)
                        else -> facade.sendTextMessage(chatId, content.body.value)
                    }
                }
            }
            is OutgoingContent.CallAction, is OutgoingContent.Story ->
                return SendResult.Failure("Non supporté par Telegram dans ce client")
        }
    }

    private suspend fun sendViaFacade(
        conversationId: String,
        accountId: String?,
        send: suspend (TdLibFacade, Long) -> TdSendResult,
    ): SendResult {
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
                finalizeTdSend(accId, chatId, send(session.facade, chatId))
            } catch (e: NetworkBlockedException) {
                SendResult.Failure(e.message ?: "Réseau indisponible")
            }
        }
    }

    override fun observeContacts(accountId: String): Flow<List<Contact>> =
        repository.observeContacts(accountId)

    override suspend fun refreshContacts(accountId: String): Result<Int> {
        val session = sessions[accountId]
            ?: return Result.failure(IllegalStateException("Telegram non connecté"))
        if (!session.nativeAvailable) {
            return Result.failure(IllegalStateException("TDLib indisponible"))
        }
        return withContext(session.dispatcher) {
            runCatching {
                networkGuard.assertNetworkAllowed()
                val userIds = session.facade.getContacts()
                val contacts = userIds.mapNotNull { uid ->
                    session.facade.getUser(uid)?.let { TdLibMapper.toContact(accountId, it) }
                }
                withContext(Dispatchers.IO) {
                    repository.replaceContacts(accountId, contacts)
                }
                contacts.size
            }
        }
    }

    override suspend fun getAccountProfile(accountId: String): AccountProfile? {
        val session = sessions[accountId] ?: return null
        return withContext(session.dispatcher) {
            val me = session.facade.getMe() ?: return@withContext null
            val full = session.facade.getUserFullInfo(me.id)
            val handle = me.usernames?.editableUsername?.takeIf { it.isNotBlank() }
                ?: me.usernames?.activeUsernames?.firstOrNull()?.takeIf { it.isNotBlank() }
            AccountProfile(
                accountId = accountId,
                protocol = ProtocolId.TELEGRAM,
                displayName = "${me.firstName} ${me.lastName}".trim().ifBlank {
                    handle ?: me.phoneNumber.ifBlank { accountId }
                },
                handle = handle?.let { "@$it" },
                phone = me.phoneNumber.takeIf { it.isNotBlank() },
                bio = full?.bio?.text?.takeIf { it.isNotBlank() },
                avatarLocalPath = me.profilePhoto?.small?.local?.path?.takeIf { it.isNotBlank() },
            )
        }
    }

    override suspend fun updateAccountProfile(
        accountId: String,
        displayName: String,
        bio: String?,
    ): Result<Unit> {
        val session = sessions[accountId]
            ?: return Result.failure(IllegalStateException("Telegram non connecté"))
        return withContext(session.dispatcher) {
            runCatching {
                networkGuard.assertNetworkAllowed()
                val parts = displayName.trim().split(Regex("\\s+"), limit = 2)
                val first = parts.getOrNull(0).orEmpty().ifBlank { "User" }
                val last = parts.getOrNull(1).orEmpty()
                session.facade.setName(first, last)?.let { throw IllegalStateException(it) }
                if (bio != null) {
                    session.facade.setBio(bio)?.let { throw IllegalStateException(it) }
                }
            }
        }
    }

    /** Uploads a local image as the account profile photo. */
    suspend fun setProfilePhoto(accountId: String, localPath: String): Result<Unit> {
        val session = sessions[accountId]
            ?: return Result.failure(IllegalStateException("Telegram non connecté"))
        return withContext(session.dispatcher) {
            runCatching {
                networkGuard.assertNetworkAllowed()
                session.facade.setProfilePhoto(localPath)?.let { throw IllegalStateException(it) }
                Unit
            }
        }
    }

    /**
     * Looks up a Telegram user by phone (E.164 preferred) and opens/creates their private chat.
     * Useful for NewChat when the remote id is a phone number.
     */
    suspend fun searchUserByPhoneNumber(
        phoneNumber: String,
        accountId: String? = null,
    ): Result<Contact> {
        val accId = accountId
            ?: sessions.keys.singleOrNull()
            ?: return Result.failure(
                IllegalStateException(
                    if (sessions.isEmpty()) "Telegram non connecté"
                    else "Plusieurs comptes Telegram — précise accountId",
                ),
            )
        val session = sessions[accId]
            ?: return Result.failure(IllegalStateException("Telegram non connecté"))
        return withContext(session.dispatcher) {
            runCatching {
                networkGuard.assertNetworkAllowed()
                val normalized = phoneNumber.trim()
                val user = session.facade.searchUserByPhoneNumber(normalized)
                    ?: throw IllegalStateException("Aucun utilisateur pour $normalized")
                val chat = session.facade.createPrivateChat(user.id)
                if (chat != null) {
                    withContext(Dispatchers.IO) {
                        repository.upsertConversation(TdLibMapper.toConversation(accId, chat))
                    }
                }
                val contact = TdLibMapper.toContact(accId, user)
                withContext(Dispatchers.IO) {
                    val existing = repository.observeContacts(accId).first()
                    if (existing.none { it.remoteId == contact.remoteId }) {
                        repository.replaceContacts(accId, existing + contact)
                    }
                }
                contact
            }
        }
    }

    /** Optional phonebook import via [TdApi.ImportContacts]. */
    suspend fun importContacts(
        accountId: String,
        entries: List<Triple<String, String, String>>,
    ): Result<Int> {
        val session = sessions[accountId]
            ?: return Result.failure(IllegalStateException("Telegram non connecté"))
        return withContext(session.dispatcher) {
            runCatching {
                networkGuard.assertNetworkAllowed()
                val imported = entries.map { (phone, first, last) ->
                    TdApi.ImportedContact(
                        phone,
                        first,
                        last,
                        TdApi.FormattedText("", emptyArray()),
                    )
                }.toTypedArray()
                val result = session.facade.importContacts(imported)
                    ?: throw IllegalStateException("Import contacts échoué")
                refreshContacts(accountId)
                result.userIds?.count { it != 0L } ?: 0
            }
        }
    }

    suspend fun votePoll(
        conversationId: String,
        messageId: String,
        optionIds: IntArray,
    ): Result<Unit> {
        val session = sessionForConversation(conversationId)
            ?: return Result.failure(IllegalStateException("Telegram non connecté"))
        val chatId = TdLibMapper.chatIdFromConversation(conversationId)
            ?: return Result.failure(IllegalStateException("Conversation invalide"))
        val tdMessageId = messageId.substringAfterLast('_').toLongOrNull()
            ?: return Result.failure(IllegalStateException("Message invalide"))
        return withContext(session.dispatcher) {
            runCatching {
                networkGuard.assertNetworkAllowed()
                session.facade.setPollAnswer(chatId, tdMessageId, optionIds)
                    ?.let { throw IllegalStateException(it) }
                Unit
            }
        }
    }

    suspend fun addReaction(
        conversationId: String,
        messageId: String,
        emoji: String,
    ): Result<Unit> {
        val session = sessionForConversation(conversationId)
            ?: return Result.failure(IllegalStateException("Telegram non connecté"))
        val chatId = TdLibMapper.chatIdFromConversation(conversationId)
            ?: return Result.failure(IllegalStateException("Conversation invalide"))
        val tdMessageId = messageId.substringAfterLast('_').toLongOrNull()
            ?: return Result.failure(IllegalStateException("Message invalide"))
        return withContext(session.dispatcher) {
            runCatching {
                networkGuard.assertNetworkAllowed()
                session.facade.addMessageReaction(chatId, tdMessageId, emoji)
                    ?.let { throw IllegalStateException(it) }
                val existing = repository.getMessage(messageId)
                if (existing != null) {
                    val json = runCatching {
                        org.json.JSONObject(existing.payloadJson ?: "{}")
                    }.getOrElse { org.json.JSONObject() }
                    val arr = json.optJSONArray("reactions") ?: org.json.JSONArray()
                    arr.put(emoji)
                    json.put("reactions", arr)
                    repository.upsertMessage(existing.copy(payloadJson = json.toString()))
                }
                Unit
            }
        }
    }

    suspend fun removeReaction(
        conversationId: String,
        messageId: String,
        emoji: String,
    ): Result<Unit> {
        val session = sessionForConversation(conversationId)
            ?: return Result.failure(IllegalStateException("Telegram non connecté"))
        val chatId = TdLibMapper.chatIdFromConversation(conversationId)
            ?: return Result.failure(IllegalStateException("Conversation invalide"))
        val tdMessageId = messageId.substringAfterLast('_').toLongOrNull()
            ?: return Result.failure(IllegalStateException("Message invalide"))
        return withContext(session.dispatcher) {
            runCatching {
                networkGuard.assertNetworkAllowed()
                session.facade.removeMessageReaction(chatId, tdMessageId, emoji)
                    ?.let { throw IllegalStateException(it) }
                Unit
            }
        }
    }

    /**
     * Sends text with TDLib reply / silent / schedule / topic / markdown-spoiler options.
     * Prefer this over local quote-only reply when [TelegramSendOptions.replyToMessageId] is set.
     */
    suspend fun sendTextAdvanced(
        conversationId: String,
        text: String,
        options: TelegramSendOptions = TelegramSendOptions(),
        parseMarkdown: Boolean = false,
        accountId: String? = null,
    ): SendResult {
        if (text.isBlank()) return SendResult.Failure("Message vide")
        return sendViaFacade(conversationId, accountId) { facade, chatId ->
            facade.sendTextMessage(chatId, text, options, parseMarkdown)
        }
    }

    suspend fun sendTextWithSpoiler(
        conversationId: String,
        text: String,
        spoilerStart: Int,
        spoilerLength: Int,
        options: TelegramSendOptions = TelegramSendOptions(),
        accountId: String? = null,
    ): SendResult = sendViaFacade(conversationId, accountId) { facade, chatId ->
        facade.sendTextWithSpoiler(chatId, text, spoilerStart, spoilerLength, options)
    }

    suspend fun sendVideoNote(
        conversationId: String,
        localPath: String,
        durationSec: Int = 0,
        options: TelegramSendOptions = TelegramSendOptions(),
        accountId: String? = null,
    ): SendResult = sendViaFacade(conversationId, accountId) { facade, chatId ->
        facade.sendVideoNote(chatId, localPath, durationSec, options = options)
    }

    suspend fun pinMessage(
        conversationId: String,
        messageId: String,
        disableNotification: Boolean = true,
        onlyForSelf: Boolean = false,
    ): Result<Unit> = tdMessageOp(conversationId, messageId) { facade, chatId, tdId ->
        facade.pinChatMessage(chatId, tdId, disableNotification, onlyForSelf)
    }

    suspend fun unpinMessage(conversationId: String, messageId: String): Result<Unit> =
        tdMessageOp(conversationId, messageId) { facade, chatId, tdId ->
            facade.unpinChatMessage(chatId, tdId)
        }

    suspend fun unpinAllMessages(conversationId: String): Result<Unit> {
        val session = sessionForConversation(conversationId)
            ?: return Result.failure(IllegalStateException("Telegram non connecté"))
        val chatId = TdLibMapper.chatIdFromConversation(conversationId)
            ?: return Result.failure(IllegalStateException("Conversation invalide"))
        return withContext(session.dispatcher) {
            runCatching {
                networkGuard.assertNetworkAllowed()
                session.facade.unpinAllChatMessages(chatId)?.let { throw IllegalStateException(it) }
                Unit
            }
        }
    }

    suspend fun stopPoll(conversationId: String, messageId: String): Result<Unit> =
        tdMessageOp(conversationId, messageId) { facade, chatId, tdId ->
            facade.stopPoll(chatId, tdId)
        }

    suspend fun editMessageCaption(
        conversationId: String,
        messageId: String,
        caption: String,
    ): Result<Unit> = tdMessageOp(conversationId, messageId) { facade, chatId, tdId ->
        facade.editMessageCaption(chatId, tdId, caption)
    }

    suspend fun leaveConversation(conversationId: String): Result<Unit> {
        val session = sessionForConversation(conversationId)
            ?: return Result.failure(IllegalStateException("Telegram non connecté"))
        val chatId = TdLibMapper.chatIdFromConversation(conversationId)
            ?: return Result.failure(IllegalStateException("Conversation invalide"))
        return withContext(session.dispatcher) {
            runCatching {
                networkGuard.assertNetworkAllowed()
                session.facade.leaveChat(chatId)?.let { throw IllegalStateException(it) }
                Unit
            }
        }
    }

    suspend fun joinByInviteLink(accountId: String, inviteLink: String): Result<String> {
        val session = sessions[accountId]
            ?: return Result.failure(IllegalStateException("Telegram non connecté"))
        return withContext(session.dispatcher) {
            runCatching {
                networkGuard.assertNetworkAllowed()
                when (val result = session.facade.joinChatByInviteLink(inviteLink)) {
                    is TdApi.ChatJoinResultSuccess -> {
                        val chat = session.facade.getChat(result.chatId)
                        if (chat != null) {
                            withContext(Dispatchers.IO) {
                                repository.upsertConversation(TdLibMapper.toConversation(accountId, chat))
                            }
                        }
                        TdLibMapper.conversationId(accountId, result.chatId)
                    }
                    is TdApi.ChatJoinResultRequestSent ->
                        throw IllegalStateException("Demande d'adhésion envoyée")
                    is TdApi.ChatJoinResultDeclined ->
                        throw IllegalStateException("Invitation refusée")
                    null -> throw IllegalStateException("Échec de jointure")
                    else -> throw IllegalStateException("Résultat: ${result::class.simpleName}")
                }
            }
        }
    }

    suspend fun setUserBlocked(accountId: String, userId: Long, blocked: Boolean): Result<Unit> {
        val session = sessions[accountId]
            ?: return Result.failure(IllegalStateException("Telegram non connecté"))
        return withContext(session.dispatcher) {
            runCatching {
                networkGuard.assertNetworkAllowed()
                session.facade.setUserBlocked(userId, blocked)
                    ?.let { throw IllegalStateException(it) }
                Unit
            }
        }
    }

    suspend fun setMarkedAsUnread(conversationId: String, marked: Boolean): Result<Unit> {
        val session = sessionForConversation(conversationId)
            ?: return Result.failure(IllegalStateException("Telegram non connecté"))
        val chatId = TdLibMapper.chatIdFromConversation(conversationId)
            ?: return Result.failure(IllegalStateException("Conversation invalide"))
        return withContext(session.dispatcher) {
            runCatching {
                networkGuard.assertNetworkAllowed()
                session.facade.toggleChatMarkedAsUnread(chatId, marked)
                    ?.let { throw IllegalStateException(it) }
                Unit
            }
        }
    }

    suspend fun setDraft(
        conversationId: String,
        text: String,
        replyToMessageId: String? = null,
    ): Result<Unit> {
        val session = sessionForConversation(conversationId)
            ?: return Result.failure(IllegalStateException("Telegram non connecté"))
        val chatId = TdLibMapper.chatIdFromConversation(conversationId)
            ?: return Result.failure(IllegalStateException("Conversation invalide"))
        val replyTd = replyToMessageId?.substringAfterLast('_')?.toLongOrNull()
        return withContext(session.dispatcher) {
            runCatching {
                networkGuard.assertNetworkAllowed()
                session.facade.setChatDraft(chatId, text, replyTd)
                    ?.let { throw IllegalStateException(it) }
                Unit
            }
        }
    }

    suspend fun searchInChat(
        conversationId: String,
        query: String,
        limit: Int = 50,
    ): Result<List<TelegramSearchHit>> {
        val session = sessionForConversation(conversationId)
            ?: return Result.failure(IllegalStateException("Telegram non connecté"))
        val chatId = TdLibMapper.chatIdFromConversation(conversationId)
            ?: return Result.failure(IllegalStateException("Conversation invalide"))
        val accId = TdLibMapper.accountIdFromConversation(conversationId)
            ?: return Result.failure(IllegalStateException("Compte invalide"))
        return withContext(session.dispatcher) {
            runCatching {
                networkGuard.assertNetworkAllowed()
                val found = session.facade.searchChatMessages(chatId, query, limit = limit)
                    ?: throw IllegalStateException("Recherche échouée")
                found.messages?.map { msg ->
                    val conv = TdLibMapper.conversationId(accId, msg.chatId)
                    TelegramSearchHit(
                        conversationId = conv,
                        messageId = TdLibMapper.messageId(conv, msg.id),
                        chatId = msg.chatId,
                        tdMessageId = msg.id,
                        preview = TdLibMapper.messageBody(msg),
                        timestampMs = msg.date * 1000L,
                    )
                }.orEmpty()
            }
        }
    }

    suspend fun searchGlobal(
        accountId: String,
        query: String,
        limit: Int = 50,
    ): Result<List<TelegramSearchHit>> {
        val session = sessions[accountId]
            ?: return Result.failure(IllegalStateException("Telegram non connecté"))
        return withContext(session.dispatcher) {
            runCatching {
                networkGuard.assertNetworkAllowed()
                val found = session.facade.searchMessages(query, limit)
                    ?: throw IllegalStateException("Recherche globale échouée")
                found.messages?.map { msg ->
                    val conv = TdLibMapper.conversationId(accountId, msg.chatId)
                    TelegramSearchHit(
                        conversationId = conv,
                        messageId = TdLibMapper.messageId(conv, msg.id),
                        chatId = msg.chatId,
                        tdMessageId = msg.id,
                        preview = TdLibMapper.messageBody(msg),
                        timestampMs = msg.date * 1000L,
                    )
                }.orEmpty()
            }
        }
    }

    suspend fun listScheduledMessages(conversationId: String): Result<List<Message>> {
        val session = sessionForConversation(conversationId)
            ?: return Result.failure(IllegalStateException("Telegram non connecté"))
        val chatId = TdLibMapper.chatIdFromConversation(conversationId)
            ?: return Result.failure(IllegalStateException("Conversation invalide"))
        val accId = TdLibMapper.accountIdFromConversation(conversationId)
            ?: return Result.failure(IllegalStateException("Compte invalide"))
        return withContext(session.dispatcher) {
            runCatching {
                networkGuard.assertNetworkAllowed()
                session.facade.getChatScheduledMessages(chatId).map { TdLibMapper.toMessage(accId, it) }
            }
        }
    }

    fun listChatFolders(accountId: String): List<TelegramChatFolder> =
        sessions[accountId]?.chatFolders.orEmpty()

    suspend fun listFolderChats(accountId: String, folderId: Int): Result<List<Conversation>> {
        val session = sessions[accountId]
            ?: return Result.failure(IllegalStateException("Telegram non connecté"))
        return withContext(session.dispatcher) {
            runCatching {
                networkGuard.assertNetworkAllowed()
                session.facade.getChatsInFolder(folderId).mapNotNull { id ->
                    session.facade.getChat(id)?.let { TdLibMapper.toConversation(accountId, it) }
                }
            }
        }
    }

    suspend fun createFolder(
        accountId: String,
        title: String,
        includedConversationIds: List<String>,
    ): Result<TelegramChatFolder> {
        val session = sessions[accountId]
            ?: return Result.failure(IllegalStateException("Telegram non connecté"))
        val chatIds = includedConversationIds.mapNotNull { TdLibMapper.chatIdFromConversation(it) }
            .toLongArray()
        return withContext(session.dispatcher) {
            runCatching {
                networkGuard.assertNetworkAllowed()
                val info = session.facade.createChatFolder(title, chatIds)
                    ?: throw IllegalStateException("Création de dossier échouée")
                TelegramChatFolder(
                    id = info.id,
                    title = info.name?.text?.text.orEmpty().ifBlank { title },
                )
            }
        }
    }

    suspend fun deleteFolder(accountId: String, folderId: Int): Result<Unit> {
        val session = sessions[accountId]
            ?: return Result.failure(IllegalStateException("Telegram non connecté"))
        return withContext(session.dispatcher) {
            runCatching {
                networkGuard.assertNetworkAllowed()
                session.facade.deleteChatFolder(folderId)?.let { throw IllegalStateException(it) }
                session.chatFolders = session.chatFolders.filterNot { it.id == folderId }
                Unit
            }
        }
    }

    suspend fun listForumTopics(conversationId: String): Result<List<TelegramForumTopic>> {
        val session = sessionForConversation(conversationId)
            ?: return Result.failure(IllegalStateException("Telegram non connecté"))
        val chatId = TdLibMapper.chatIdFromConversation(conversationId)
            ?: return Result.failure(IllegalStateException("Conversation invalide"))
        return withContext(session.dispatcher) {
            runCatching {
                networkGuard.assertNetworkAllowed()
                session.facade.getForumTopics(chatId).map { topic ->
                    val info = topic.info
                    TelegramForumTopic(
                        id = info?.forumTopicId ?: 0,
                        name = info?.name.orEmpty(),
                        isGeneral = info?.isGeneral == true,
                        isClosed = info?.isClosed == true,
                        unreadCount = topic.unreadCount,
                    )
                }
            }
        }
    }

    suspend fun createForumTopic(conversationId: String, name: String): Result<TelegramForumTopic> {
        val session = sessionForConversation(conversationId)
            ?: return Result.failure(IllegalStateException("Telegram non connecté"))
        val chatId = TdLibMapper.chatIdFromConversation(conversationId)
            ?: return Result.failure(IllegalStateException("Conversation invalide"))
        return withContext(session.dispatcher) {
            runCatching {
                networkGuard.assertNetworkAllowed()
                val info = session.facade.createForumTopic(chatId, name)
                    ?: throw IllegalStateException("Création de topic échouée")
                TelegramForumTopic(
                    id = info.forumTopicId,
                    name = info.name.orEmpty(),
                    isGeneral = info.isGeneral,
                    isClosed = info.isClosed,
                    unreadCount = 0,
                )
            }
        }
    }

    suspend fun sendBotStart(
        conversationId: String,
        botUserId: Long,
        parameter: String = "",
        accountId: String? = null,
    ): SendResult = sendViaFacade(conversationId, accountId) { facade, chatId ->
        facade.sendBotStartMessage(botUserId, chatId, parameter)
    }

    suspend fun answerCallbackButton(
        conversationId: String,
        messageId: String,
        data: String,
    ): Result<TelegramCallbackAnswer> {
        val session = sessionForConversation(conversationId)
            ?: return Result.failure(IllegalStateException("Telegram non connecté"))
        val chatId = TdLibMapper.chatIdFromConversation(conversationId)
            ?: return Result.failure(IllegalStateException("Conversation invalide"))
        val tdId = messageId.substringAfterLast('_').toLongOrNull()
            ?: return Result.failure(IllegalStateException("Message invalide"))
        return withContext(session.dispatcher) {
            runCatching {
                networkGuard.assertNetworkAllowed()
                session.facade.getCallbackQueryAnswer(
                    chatId,
                    tdId,
                    data.toByteArray(StandardCharsets.UTF_8),
                ) ?: throw IllegalStateException("Callback échoué")
            }
        }
    }

    suspend fun createSupergroupOrChannel(
        accountId: String,
        title: String,
        isChannel: Boolean,
        description: String = "",
        isForum: Boolean = false,
    ): Result<String> {
        val session = sessions[accountId]
            ?: return Result.failure(IllegalStateException("Telegram non connecté"))
        return withContext(session.dispatcher) {
            runCatching {
                networkGuard.assertNetworkAllowed()
                val chat = session.facade.createNewSupergroupChat(
                    title,
                    isChannel,
                    description,
                    isForum,
                ) ?: throw IllegalStateException("Création échouée")
                withContext(Dispatchers.IO) {
                    repository.upsertConversation(TdLibMapper.toConversation(accountId, chat))
                }
                TdLibMapper.conversationId(accountId, chat.id)
            }
        }
    }

    suspend fun setChatTitleDescription(
        conversationId: String,
        title: String? = null,
        description: String? = null,
    ): Result<Unit> {
        val session = sessionForConversation(conversationId)
            ?: return Result.failure(IllegalStateException("Telegram non connecté"))
        val chatId = TdLibMapper.chatIdFromConversation(conversationId)
            ?: return Result.failure(IllegalStateException("Conversation invalide"))
        return withContext(session.dispatcher) {
            runCatching {
                networkGuard.assertNetworkAllowed()
                if (title != null) {
                    session.facade.setChatTitle(chatId, title)?.let { throw IllegalStateException(it) }
                }
                if (description != null) {
                    session.facade.setChatDescription(chatId, description)
                        ?.let { throw IllegalStateException(it) }
                }
                Unit
            }
        }
    }

    /** Loads active story lists (receive path). Sending stories remains product-gated. */
    suspend fun refreshActiveStories(accountId: String): Result<Unit> {
        val session = sessions[accountId]
            ?: return Result.failure(IllegalStateException("Telegram non connecté"))
        return withContext(session.dispatcher) {
            runCatching {
                networkGuard.assertNetworkAllowed()
                session.facade.loadActiveStories()?.let { throw IllegalStateException(it) }
                Unit
            }
        }
    }

    suspend fun sendAlbum(
        conversationId: String,
        files: List<Pair<String, String>>,
        caption: String? = null,
        options: TelegramSendOptions = TelegramSendOptions(),
        accountId: String? = null,
    ): SendResult {
        if (files.size < 2) return SendResult.Failure("Un album nécessite au moins 2 fichiers")
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
                val messages = session.facade.sendMessageAlbum(chatId, files, caption, options)
                    ?: return@withContext SendResult.Failure("Envoi album échoué")
                messages.forEach { msg ->
                    withContext(Dispatchers.IO) {
                        repository.upsertMessage(TdLibMapper.toMessage(accId, msg))
                    }
                }
                val first = messages.firstOrNull()
                    ?: return@withContext SendResult.Failure("Album vide")
                SendResult.Success(TdLibMapper.messageId(conversationId, first.id))
            } catch (e: NetworkBlockedException) {
                SendResult.Failure(e.message ?: "Réseau indisponible")
            }
        }
    }

    suspend fun editLiveLocation(
        conversationId: String,
        messageId: String,
        latitude: Double,
        longitude: Double,
        livePeriodSec: Int,
    ): Result<Unit> = tdMessageOp(conversationId, messageId) { facade, chatId, tdId ->
        facade.editMessageLiveLocation(chatId, tdId, latitude, longitude, livePeriodSec)
    }

    suspend fun editForumTopic(
        conversationId: String,
        topicId: Int,
        name: String,
    ): Result<Unit> {
        val session = sessionForConversation(conversationId)
            ?: return Result.failure(IllegalStateException("Telegram non connecté"))
        val chatId = TdLibMapper.chatIdFromConversation(conversationId)
            ?: return Result.failure(IllegalStateException("Conversation invalide"))
        return withContext(session.dispatcher) {
            runCatching {
                networkGuard.assertNetworkAllowed()
                session.facade.editForumTopic(chatId, topicId, name)
                    ?.let { throw IllegalStateException(it) }
                Unit
            }
        }
    }

    suspend fun deleteForumTopic(conversationId: String, topicId: Int): Result<Unit> {
        val session = sessionForConversation(conversationId)
            ?: return Result.failure(IllegalStateException("Telegram non connecté"))
        val chatId = TdLibMapper.chatIdFromConversation(conversationId)
            ?: return Result.failure(IllegalStateException("Conversation invalide"))
        return withContext(session.dispatcher) {
            runCatching {
                networkGuard.assertNetworkAllowed()
                session.facade.deleteForumTopic(chatId, topicId)
                    ?.let { throw IllegalStateException(it) }
                Unit
            }
        }
    }

    suspend fun setUsername(accountId: String, username: String): Result<Unit> {
        val session = sessions[accountId]
            ?: return Result.failure(IllegalStateException("Telegram non connecté"))
        return withContext(session.dispatcher) {
            runCatching {
                networkGuard.assertNetworkAllowed()
                session.facade.setUsername(username)?.let { throw IllegalStateException(it) }
                Unit
            }
        }
    }

    suspend fun addMembers(conversationId: String, userIds: LongArray): Result<Unit> {
        val session = sessionForConversation(conversationId)
            ?: return Result.failure(IllegalStateException("Telegram non connecté"))
        val chatId = TdLibMapper.chatIdFromConversation(conversationId)
            ?: return Result.failure(IllegalStateException("Conversation invalide"))
        return withContext(session.dispatcher) {
            runCatching {
                networkGuard.assertNetworkAllowed()
                session.facade.addChatMembers(chatId, userIds)
                    ?.let { throw IllegalStateException(it) }
                Unit
            }
        }
    }

    suspend fun banMember(
        conversationId: String,
        userId: Long,
        bannedUntilDate: Int = 0,
        revokeMessages: Boolean = false,
    ): Result<Unit> {
        val session = sessionForConversation(conversationId)
            ?: return Result.failure(IllegalStateException("Telegram non connecté"))
        val chatId = TdLibMapper.chatIdFromConversation(conversationId)
            ?: return Result.failure(IllegalStateException("Conversation invalide"))
        return withContext(session.dispatcher) {
            runCatching {
                networkGuard.assertNetworkAllowed()
                session.facade.banChatMember(chatId, userId, bannedUntilDate, revokeMessages)
                    ?.let { throw IllegalStateException(it) }
                Unit
            }
        }
    }

    data class InlineBotHit(val queryId: Long, val resultId: String, val title: String)

    suspend fun queryInlineBot(
        conversationId: String,
        botUserId: Long,
        query: String,
    ): Result<List<InlineBotHit>> {
        val session = sessionForConversation(conversationId)
            ?: return Result.failure(IllegalStateException("Telegram non connecté"))
        val chatId = TdLibMapper.chatIdFromConversation(conversationId)
            ?: return Result.failure(IllegalStateException("Conversation invalide"))
        return withContext(session.dispatcher) {
            runCatching {
                networkGuard.assertNetworkAllowed()
                val results = session.facade.getInlineQueryResults(botUserId, chatId, query)
                    ?: throw IllegalStateException("Inline query échouée")
                results.results?.mapNotNull { item ->
                    val id = runCatching {
                        item.javaClass.getField("id").get(item) as? String
                    }.getOrNull() ?: return@mapNotNull null
                    val title = runCatching {
                        item.javaClass.getField("title").get(item) as? String
                    }.getOrNull().orEmpty().ifBlank { id }
                    InlineBotHit(results.inlineQueryId, id, title)
                }.orEmpty()
            }
        }
    }

    suspend fun sendInlineBotResult(
        conversationId: String,
        queryId: Long,
        resultId: String,
        accountId: String? = null,
    ): SendResult = sendViaFacade(conversationId, accountId) { facade, chatId ->
        facade.sendInlineQueryResult(chatId, queryId, resultId)
    }

    suspend fun listFavoriteStickers(accountId: String): List<TelegramSticker> {
        val session = sessions[accountId] ?: return emptyList()
        return withContext(session.dispatcher) {
            runCatching {
                networkGuard.assertNetworkAllowed()
                session.facade.getFavoriteStickers().map { sticker ->
                    val file = sticker.sticker
                    var localPath = file?.local?.path?.takeIf { it.isNotBlank() }
                    if (localPath == null && file != null) {
                        localPath = session.facade.downloadFile(file.id)?.local?.path
                            ?.takeIf { it.isNotBlank() }
                    }
                    TelegramSticker(
                        id = sticker.id,
                        emoji = sticker.emoji.orEmpty().ifBlank { "⭐" },
                        localPath = localPath,
                    )
                }
            }.getOrElse { emptyList() }
        }
    }

    suspend fun addFavoriteSticker(accountId: String, fileId: Int): Result<Unit> {
        val session = sessions[accountId]
            ?: return Result.failure(IllegalStateException("Telegram non connecté"))
        return withContext(session.dispatcher) {
            runCatching {
                networkGuard.assertNetworkAllowed()
                session.facade.addFavoriteSticker(fileId)?.let { throw IllegalStateException(it) }
                Unit
            }
        }
    }

    suspend fun loadMessageThread(
        conversationId: String,
        messageId: String,
        limit: Int = 50,
    ): Result<List<Message>> {
        val session = sessionForConversation(conversationId)
            ?: return Result.failure(IllegalStateException("Telegram non connecté"))
        val chatId = TdLibMapper.chatIdFromConversation(conversationId)
            ?: return Result.failure(IllegalStateException("Conversation invalide"))
        val accId = TdLibMapper.accountIdFromConversation(conversationId)
            ?: return Result.failure(IllegalStateException("Compte invalide"))
        val tdId = messageId.substringAfterLast('_').toLongOrNull()
            ?: return Result.failure(IllegalStateException("Message invalide"))
        return withContext(session.dispatcher) {
            runCatching {
                networkGuard.assertNetworkAllowed()
                val msgs = session.facade.getMessageThreadHistory(chatId, tdId, limit = limit)
                    ?: throw IllegalStateException("Thread introuvable")
                msgs.map { TdLibMapper.toMessage(accId, it) }
            }
        }
    }

    suspend fun openBotWebApp(
        conversationId: String,
        botUserId: Long,
        url: String,
    ): Result<Pair<Long, String>> {
        val session = sessionForConversation(conversationId)
            ?: return Result.failure(IllegalStateException("Telegram non connecté"))
        val chatId = TdLibMapper.chatIdFromConversation(conversationId)
            ?: return Result.failure(IllegalStateException("Conversation invalide"))
        return withContext(session.dispatcher) {
            runCatching {
                networkGuard.assertNetworkAllowed()
                session.facade.openWebApp(chatId, botUserId, url)
                    ?: throw IllegalStateException("Ouverture WebApp échouée")
            }
        }
    }

    suspend fun sendGame(
        conversationId: String,
        botUserId: Long,
        gameShortName: String,
        accountId: String? = null,
    ): SendResult = sendViaFacade(conversationId, accountId) { facade, chatId ->
        facade.sendGame(chatId, botUserId, gameShortName)
    }

    private suspend fun tdMessageOp(
        conversationId: String,
        messageId: String,
        block: suspend (TdLibFacade, Long, Long) -> String?,
    ): Result<Unit> {
        val session = sessionForConversation(conversationId)
            ?: return Result.failure(IllegalStateException("Telegram non connecté"))
        val chatId = TdLibMapper.chatIdFromConversation(conversationId)
            ?: return Result.failure(IllegalStateException("Conversation invalide"))
        val tdMessageId = messageId.substringAfterLast('_').toLongOrNull()
            ?: return Result.failure(IllegalStateException("Message invalide"))
        return withContext(session.dispatcher) {
            runCatching {
                networkGuard.assertNetworkAllowed()
                block(session.facade, chatId, tdMessageId)?.let { throw IllegalStateException(it) }
                Unit
            }
        }
    }

    suspend fun editMessage(
        conversationId: String,
        messageId: String,
        newBody: String,
    ): Result<Unit> {
        val session = sessionForConversation(conversationId)
            ?: return Result.failure(IllegalStateException("Telegram non connecté"))
        val chatId = TdLibMapper.chatIdFromConversation(conversationId)
            ?: return Result.failure(IllegalStateException("Conversation invalide"))
        val tdMessageId = messageId.substringAfterLast('_').toLongOrNull()
            ?: return Result.failure(IllegalStateException("Message invalide"))
        return withContext(session.dispatcher) {
            runCatching {
                networkGuard.assertNetworkAllowed()
                session.facade.editMessageText(chatId, tdMessageId, newBody)
                    ?.let { throw IllegalStateException(it) }
                val existing = repository.getMessage(messageId)
                if (existing != null) {
                    repository.upsertMessage(existing.copy(body = newBody))
                }
                Unit
            }
        }
    }

    suspend fun deleteMessages(
        conversationId: String,
        messageIds: List<String>,
        revoke: Boolean = true,
    ): Result<Unit> {
        val session = sessionForConversation(conversationId)
            ?: return Result.failure(IllegalStateException("Telegram non connecté"))
        val chatId = TdLibMapper.chatIdFromConversation(conversationId)
            ?: return Result.failure(IllegalStateException("Conversation invalide"))
        val tdIds = messageIds.mapNotNull { it.substringAfterLast('_').toLongOrNull() }.toLongArray()
        if (tdIds.isEmpty()) return Result.failure(IllegalStateException("Message invalide"))
        return withContext(session.dispatcher) {
            runCatching {
                networkGuard.assertNetworkAllowed()
                session.facade.deleteMessages(chatId, tdIds, revoke)
                    ?.let { throw IllegalStateException(it) }
                repository.deleteMessages(messageIds)
                Unit
            }
        }
    }

    suspend fun setChatMuted(
        conversationId: String,
        muteForSeconds: Int,
    ): Result<Unit> {
        val session = sessionForConversation(conversationId)
            ?: return Result.failure(IllegalStateException("Telegram non connecté"))
        val chatId = TdLibMapper.chatIdFromConversation(conversationId)
            ?: return Result.failure(IllegalStateException("Conversation invalide"))
        return withContext(session.dispatcher) {
            runCatching {
                networkGuard.assertNetworkAllowed()
                session.facade.setChatNotificationMute(chatId, muteForSeconds)
                    ?.let { throw IllegalStateException(it) }
                Unit
            }
        }
    }

    suspend fun setChatPinned(conversationId: String, pinned: Boolean): Result<Unit> {
        val session = sessionForConversation(conversationId)
            ?: return Result.failure(IllegalStateException("Telegram non connecté"))
        val chatId = TdLibMapper.chatIdFromConversation(conversationId)
            ?: return Result.failure(IllegalStateException("Conversation invalide"))
        return withContext(session.dispatcher) {
            runCatching {
                networkGuard.assertNetworkAllowed()
                session.facade.toggleChatIsPinned(chatId, pinned)
                    ?.let { throw IllegalStateException(it) }
                Unit
            }
        }
    }

    suspend fun setChatArchived(conversationId: String, archived: Boolean): Result<Unit> {
        val session = sessionForConversation(conversationId)
            ?: return Result.failure(IllegalStateException("Telegram non connecté"))
        val chatId = TdLibMapper.chatIdFromConversation(conversationId)
            ?: return Result.failure(IllegalStateException("Conversation invalide"))
        return withContext(session.dispatcher) {
            runCatching {
                networkGuard.assertNetworkAllowed()
                session.facade.setChatArchived(chatId, archived)
                    ?.let { throw IllegalStateException(it) }
                Unit
            }
        }
    }

    suspend fun forwardMessages(
        toConversationId: String,
        fromConversationId: String,
        messageIds: List<String>,
    ): Result<Unit> {
        val fromAcc = TdLibMapper.accountIdFromConversation(fromConversationId)
        val toAcc = TdLibMapper.accountIdFromConversation(toConversationId)
        if (fromAcc == null || toAcc == null || fromAcc != toAcc) {
            return Result.failure(
                IllegalStateException("Transfert impossible entre comptes Telegram différents"),
            )
        }
        val toSession = sessionForConversation(toConversationId)
            ?: return Result.failure(IllegalStateException("Telegram non connecté"))
        val toChatId = TdLibMapper.chatIdFromConversation(toConversationId)
            ?: return Result.failure(IllegalStateException("Destination invalide"))
        val fromChatId = TdLibMapper.chatIdFromConversation(fromConversationId)
            ?: return Result.failure(IllegalStateException("Source invalide"))
        val tdIds = messageIds.mapNotNull { it.substringAfterLast('_').toLongOrNull() }.toLongArray()
        if (tdIds.isEmpty()) return Result.failure(IllegalStateException("Aucun message"))
        return withContext(toSession.dispatcher) {
            runCatching {
                networkGuard.assertNetworkAllowed()
                toSession.facade.forwardMessages(toChatId, fromChatId, tdIds)
                    ?.let { throw IllegalStateException(it) }
                Unit
            }
        }
    }

    /**
     * Lists stickers from installed packs (covers + [GetStickers]), downloading files when needed.
     */
    suspend fun listStickers(
        accountId: String? = null,
        query: String = "",
        limit: Int = 40,
    ): List<TelegramSticker> {
        val accId = when {
            accountId != null -> accountId
            sessions.size == 1 -> sessions.keys.single()
            else -> return emptyList()
        }
        val session = sessions[accId] ?: return emptyList()
        return withContext(session.dispatcher) {
            runCatching {
                networkGuard.assertNetworkAllowed()
                val fromSearch = session.facade.getStickers(query, limit)
                val fromSets = if (fromSearch.isEmpty() || query.isBlank()) {
                    session.facade.getInstalledStickerSets()
                        .take(5)
                        .flatMap { info ->
                            val set = session.facade.getStickerSet(info.id)
                            set?.stickers?.toList().orEmpty().take(8)
                        }
                } else {
                    emptyList()
                }
                val stickers = (fromSearch + fromSets)
                    .distinctBy { it.id }
                    .take(limit)
                stickers.map { sticker ->
                    val file = sticker.sticker
                    var localPath = file?.local?.path?.takeIf { it.isNotBlank() }
                    if (localPath == null && file != null) {
                        val downloaded = session.facade.downloadFile(file.id)
                        localPath = downloaded?.local?.path?.takeIf { it.isNotBlank() }
                    }
                    TelegramSticker(
                        id = sticker.id,
                        emoji = sticker.emoji.orEmpty().ifBlank { "⭐" },
                        localPath = localPath,
                    )
                }
            }.getOrElse {
                Timber.w(it, "listStickers failed")
                emptyList()
            }
        }
    }

    /** Sets chat-wide auto-delete TTL (seconds); 0 disables. */
    suspend fun setChatMessageAutoDeleteTime(
        conversationId: String,
        expireSeconds: Int,
    ): Result<Unit> {
        val session = sessionForConversation(conversationId)
            ?: return Result.failure(IllegalStateException("Telegram non connecté"))
        val chatId = TdLibMapper.chatIdFromConversation(conversationId)
            ?: return Result.failure(IllegalStateException("Conversation invalide"))
        return withContext(session.dispatcher) {
            runCatching {
                networkGuard.assertNetworkAllowed()
                session.facade.setChatMessageAutoDeleteTime(chatId, expireSeconds)
                    ?.let { throw IllegalStateException(it) }
                Unit
            }
        }
    }

    /**
     * Sends a self-destructing photo/voice (per-message timer, independent of chat TTL).
     */
    suspend fun sendSelfDestructMedia(
        conversationId: String,
        localPath: String,
        mimeType: String,
        expireSeconds: Int,
        caption: String? = null,
        durationMs: Int = 0,
        accountId: String? = null,
    ): SendResult {
        if (expireSeconds <= 0) return SendResult.Failure("Durée d'expiration invalide")
        return when {
            mimeType.startsWith("audio/") || mimeType.contains("ogg") || mimeType.contains("voice") ->
                sendViaFacade(conversationId, accountId) { facade, chatId ->
                    facade.sendVoiceNote(chatId, localPath, durationMs, expireSeconds)
                }
            mimeType.startsWith("image/") ->
                sendViaFacade(conversationId, accountId) { facade, chatId ->
                    facade.sendPhoto(chatId, localPath, caption, expireSeconds)
                }
            else ->
                sendViaFacade(conversationId, accountId) { facade, chatId ->
                    facade.sendMedia(chatId, localPath, mimeType, caption, expireSeconds)
                }
        }
    }

    override suspend fun setTyping(conversationId: String, typing: Boolean) {
        val session = sessionForConversation(conversationId) ?: return
        val chatId = TdLibMapper.chatIdFromConversation(conversationId) ?: return
        withContext(session.dispatcher) {
            session.facade.setTyping(chatId, typing)
        }
    }

    override suspend fun markRead(conversationId: String, messageId: String?) {
        val session = sessionForConversation(conversationId) ?: return
        val chatId = TdLibMapper.chatIdFromConversation(conversationId) ?: return
        val tdMessageId = messageId?.substringAfterLast('_')?.toLongOrNull()
            ?: withContext(Dispatchers.IO) {
                repository.latestMessageId(conversationId)
                    ?.substringAfterLast('_')
                    ?.toLongOrNull()
            }
            ?: return
        withContext(session.dispatcher) {
            session.facade.viewMessages(chatId, longArrayOf(tdMessageId))
        }
    }

    override suspend fun exportBackup(
        accountId: String,
        destinationPath: String,
    ): BackupExportResult = withContext(Dispatchers.IO) {
        runCatching {
            val (convs, messages) = repository.exportSnapshot(accountId)
            val json = buildString {
                append("{\"protocol\":\"TELEGRAM\",\"accountId\":")
                append(JSONObject.quote(accountId))
                append(",\"exportedAt\":")
                append(System.currentTimeMillis())
                append(",\"conversations\":[")
                convs.forEachIndexed { i, c ->
                    if (i > 0) append(',')
                    append("{\"id\":").append(JSONObject.quote(c.id))
                    append(",\"title\":").append(JSONObject.quote(c.title))
                    append(",\"remoteId\":").append(JSONObject.quote(c.remoteId))
                    append('}')
                }
                append("],\"messages\":[")
                messages.forEachIndexed { i, m ->
                    if (i > 0) append(',')
                    append("{\"id\":").append(JSONObject.quote(m.id))
                    append(",\"conversationId\":").append(JSONObject.quote(m.conversationId))
                    append(",\"body\":").append(JSONObject.quote(m.body))
                    append(",\"timestamp\":").append(m.timestamp)
                    append(",\"direction\":").append(JSONObject.quote(m.direction.name))
                    append(",\"kind\":").append(JSONObject.quote(m.kind.name))
                    append('}')
                }
                append("]}")
            }
            val out = File(destinationPath)
            out.parentFile?.mkdirs()
            FileOutputStream(out).use { fos ->
                fos.write(json.toByteArray(StandardCharsets.UTF_8))
            }
            BackupExportResult.Success(
                uriOrPath = out.absolutePath,
                messageCount = messages.size,
                conversationCount = convs.size,
            )
        }.getOrElse { e ->
            BackupExportResult.Failure(e.message ?: "Export failed")
        }
    }

    override suspend fun wipeLocalData(accountId: String) {
        withContext(Dispatchers.IO) {
            runCatching { File(context.filesDir, "tdlib_$accountId").deleteRecursively() }
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
                typingFlows.keys.filter { it.startsWith("${id}_") }.forEach { typingFlows.remove(it) }
                if (authenticatingAccountId == id) {
                    authenticatingAccountId = null
                    republishPendingAuthStep()
                }
                // Soft-disconnect: Room must not stay CONNECTED without a live session.
                // ConnectionManager wipe rules keep provisioned secrets / mid-auth rows.
                val hasSecrets = credentialStore.getAllForAccount(id)
                    .filterKeys { !it.startsWith("__") }
                    .isNotEmpty()
                val roomState = if (hasSecrets) {
                    ConnectionState.CONNECTING
                } else {
                    ConnectionState.DISCONNECTED
                }
                val existing = repository.observeAccounts().first().firstOrNull { it.id == id }
                repository.upsertAccount(
                    ltechnologies.onionphone.securemessenger.core.model.Account(
                        id = id,
                        protocol = ProtocolId.TELEGRAM,
                        displayName = existing?.displayName ?: id,
                        connectionState = roomState,
                    ),
                )
            }
            if (accountId == null) {
                typingFlows.clear()
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

    /** Re-applies the Tor SOCKS proxy on every live Telegram session. Tor stays mandatory. */
    suspend fun reapplyProxy(config: ProxyConfig) {
        val accountIds = sessions.keys.toList()
        for (accountId in accountIds) {
            val session = sessions[accountId] ?: continue
            if (!config.torRequired) {
                _lastAuthError.value = "Tor requis : démarrez OnionVPN pour Telegram"
                disconnect(accountId)
                continue
            }
            val proxyOk = withContext(session.dispatcher) {
                session.facade.configureProxy(config.host, config.port, config.username, config.password)
            }
            if (!proxyOk) {
                _lastAuthError.value = "Tor requis : démarrez OnionVPN pour Telegram"
                disconnect(accountId)
            }
        }
        refreshConnectionState()
    }

    companion object {
        private const val HISTORY_PAGE_SIZE = 100
        /** Soft cap only — EOF is empty page, not short page (see TDLib getChatHistory). */
        private const val HISTORY_MAX_PAGES = 200
        private const val CHAT_SYNC_LIMIT = 200

        fun conversationIdFor(accountId: String, chatId: Long) =
            TdLibMapper.conversationId(accountId, chatId)
    }
}
