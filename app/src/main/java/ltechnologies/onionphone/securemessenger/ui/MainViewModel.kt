package ltechnologies.onionphone.securemessenger.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import ltechnologies.onionphone.securemessenger.core.model.AccountCredentials
import ltechnologies.onionphone.securemessenger.core.model.AccountProfile
import ltechnologies.onionphone.securemessenger.core.model.BackupExportResult
import ltechnologies.onionphone.securemessenger.core.model.ConnectionResult
import ltechnologies.onionphone.securemessenger.core.model.ConnectionState
import ltechnologies.onionphone.securemessenger.core.model.Attachment
import ltechnologies.onionphone.securemessenger.core.model.Contact
import ltechnologies.onionphone.securemessenger.core.model.Conversation
import ltechnologies.onionphone.securemessenger.core.model.FeatureFlags
import ltechnologies.onionphone.securemessenger.core.model.HistoryLoadResult
import ltechnologies.onionphone.securemessenger.core.model.Message
import ltechnologies.onionphone.securemessenger.core.model.OutgoingContent
import ltechnologies.onionphone.securemessenger.core.model.ProtocolCapabilities
import ltechnologies.onionphone.securemessenger.core.model.ProtocolId
import ltechnologies.onionphone.securemessenger.core.model.ProxyConfig
import ltechnologies.onionphone.securemessenger.core.model.RegistrationRequest
import ltechnologies.onionphone.securemessenger.core.model.RegistrationResult
import ltechnologies.onionphone.securemessenger.core.model.SanitizedText
import ltechnologies.onionphone.securemessenger.core.model.SendResult
import ltechnologies.onionphone.securemessenger.core.proxy.OnionVpnHelper
import ltechnologies.onionphone.securemessenger.core.proxy.ProxyManager
import ltechnologies.onionphone.securemessenger.core.proxy.ProxyStatus
import ltechnologies.onionphone.securemessenger.core.proxy.SocksEndpointResolver
import ltechnologies.onionphone.securemessenger.core.security.MessageSanitizer
import ltechnologies.onionphone.securemessenger.data.LocalBackupExporter
import ltechnologies.onionphone.securemessenger.data.MessengerRepository
import ltechnologies.onionphone.securemessenger.protocol.telegram.TelegramProtocol
import ltechnologies.onionphone.securemessenger.service.ConnectionManager

@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: MessengerRepository,
    private val backupExporter: LocalBackupExporter,
    private val connectionManager: ConnectionManager,
    private val proxyManager: ProxyManager,
    private val onionVpnHelper: OnionVpnHelper,
) : ViewModel() {

    val conversations: StateFlow<List<Conversation>> = repository.observeConversations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val accounts = repository.observeAccounts()
        .map { list ->
            list.filter { account ->
                when (account.protocol) {
                    ProtocolId.TELEGRAM,
                    ProtocolId.SIGNAL,
                    -> account.connectionState == ConnectionState.CONNECTED
                    else -> true
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val proxyStatus: StateFlow<ProxyStatus> = proxyManager.status
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), proxyManager.status.value)

    val killswitchActive = connectionManager.killswitchActive
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val messageFlows = ConcurrentHashMap<String, StateFlow<List<Message>>>()
    private val contactFlows = ConcurrentHashMap<String, StateFlow<List<Contact>>>()

    val enabledProtocols: List<ProtocolId> = FeatureFlags.enabled.toList()

    fun messagesFor(conversationId: String): StateFlow<List<Message>> =
        messageFlows.getOrPut(conversationId) {
            repository.observeMessages(conversationId)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
        }

    fun contactsFor(accountId: String): StateFlow<List<Contact>> =
        contactFlows.getOrPut(accountId) {
            repository.observeContacts(accountId)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
        }

    fun refreshContacts(accountId: String, protocol: ProtocolId, onResult: (Result<Int>) -> Unit) {
        viewModelScope.launch {
            val impl = connectionManager.protocolFor(protocol) ?: run {
                onResult(Result.failure(IllegalStateException("Protocole indisponible")))
                return@launch
            }
            onResult(impl.refreshContacts(accountId))
        }
    }

    fun loadAccountProfile(
        accountId: String,
        protocol: ProtocolId,
        onResult: (AccountProfile?) -> Unit,
    ) {
        viewModelScope.launch {
            onResult(connectionManager.protocolFor(protocol)?.getAccountProfile(accountId))
        }
    }

    fun updateAccountProfile(
        accountId: String,
        protocol: ProtocolId,
        displayName: String,
        bio: String?,
        onResult: (Result<Unit>) -> Unit,
    ) {
        viewModelScope.launch {
            val impl = connectionManager.protocolFor(protocol) ?: run {
                onResult(Result.failure(IllegalStateException("Protocole indisponible")))
                return@launch
            }
            onResult(impl.updateAccountProfile(accountId, displayName, bio))
        }
    }

    fun sendContent(
        conversationId: String,
        protocol: ProtocolId,
        content: OutgoingContent,
        onResult: (Boolean) -> Unit,
    ) {
        viewModelScope.launch {
            val protocolImpl = connectionManager.protocolFor(protocol) ?: run {
                onResult(false)
                return@launch
            }
            val result = protocolImpl.sendContent(conversationId, content)
            onResult(result is SendResult.Success)
        }
    }

    fun exportBackup(
        accountId: String,
        destinationPath: String,
        protocol: ProtocolId? = null,
        onResult: (BackupExportResult) -> Unit,
    ) {
        viewModelScope.launch {
            val resolvedProtocol = protocol
                ?: accounts.value.firstOrNull { it.id == accountId }?.protocol
                ?: ProtocolId.XMPP
            // Always stream via LocalBackupExporter — protocol exporters formerly
            // materialized the full account JSON in heap.
            onResult(backupExporter.export(accountId, resolvedProtocol, destinationPath))
        }
    }

    fun setTyping(conversationId: String, protocol: ProtocolId, typing: Boolean) {
        viewModelScope.launch {
            connectionManager.protocolFor(protocol)?.setTyping(conversationId, typing)
        }
    }

    fun markRead(conversationId: String, protocol: ProtocolId, messageId: String? = null) {
        viewModelScope.launch {
            connectionManager.protocolFor(protocol)?.markRead(conversationId, messageId)
        }
    }

    suspend fun loadMessageHistory(conversationId: String, protocol: ProtocolId): HistoryLoadResult =
        connectionManager.protocolFor(protocol)?.loadMessageHistory(conversationId)
            ?: HistoryLoadResult.Failure("Protocole indisponible")

    fun closeConversation(conversationId: String, protocol: ProtocolId) {
        messageFlows.remove(conversationId)
        viewModelScope.launch {
            connectionManager.protocolFor(protocol)?.closeConversation(conversationId)
        }
    }

    fun telegramProtocol(): TelegramProtocol? =
        connectionManager.protocolFor(ProtocolId.TELEGRAM) as? TelegramProtocol

    fun votePoll(
        conversationId: String,
        messageId: String,
        optionIds: IntArray,
        onResult: (Boolean) -> Unit = {},
    ) {
        viewModelScope.launch {
            val ok = telegramProtocol()?.votePoll(conversationId, messageId, optionIds)?.isSuccess == true
            onResult(ok)
        }
    }

    fun addReaction(
        conversationId: String,
        messageId: String,
        emoji: String,
        onResult: (Boolean) -> Unit = {},
    ) {
        viewModelScope.launch {
            val ok = telegramProtocol()?.addReaction(conversationId, messageId, emoji)?.isSuccess == true
            onResult(ok)
        }
    }

    fun forwardTelegramMessages(
        toConversationId: String,
        fromConversationId: String,
        messageIds: List<String>,
        onResult: (Boolean) -> Unit = {},
    ) {
        viewModelScope.launch {
            val ok = telegramProtocol()
                ?.forwardMessages(toConversationId, fromConversationId, messageIds)
                ?.isSuccess == true
            onResult(ok)
        }
    }

    fun listTelegramStickers(
        accountId: String? = null,
        query: String = "",
        onResult: (List<ltechnologies.onionphone.securemessenger.protocol.telegram.TelegramSticker>) -> Unit,
    ) {
        viewModelScope.launch {
            onResult(telegramProtocol()?.listStickers(accountId, query).orEmpty())
        }
    }

    fun observeTyping(conversationId: String, protocol: ProtocolId): StateFlow<List<String>> =
        connectionManager.protocolFor(protocol)?.observeTyping(conversationId)
            ?: MutableStateFlow(emptyList())

    fun searchTelegramUserByPhone(
        phoneNumber: String,
        accountId: String? = null,
        onResult: (Contact?) -> Unit,
    ) {
        viewModelScope.launch {
            onResult(
                telegramProtocol()?.searchUserByPhoneNumber(phoneNumber, accountId)?.getOrNull(),
            )
        }
    }

    fun setTelegramProfilePhoto(
        accountId: String,
        localPath: String,
        onResult: (Result<Unit>) -> Unit,
    ) {
        viewModelScope.launch {
            onResult(
                telegramProtocol()?.setProfilePhoto(accountId, localPath)
                    ?: Result.failure(IllegalStateException("Telegram non disponible")),
            )
        }
    }

    fun setTelegramChatAutoDelete(
        conversationId: String,
        expireSeconds: Int,
        onResult: (Boolean) -> Unit = {},
    ) {
        viewModelScope.launch {
            val ok = telegramProtocol()
                ?.setChatMessageAutoDeleteTime(conversationId, expireSeconds)
                ?.isSuccess == true
            onResult(ok)
        }
    }

    fun importTelegramContacts(
        accountId: String,
        entries: List<Triple<String, String, String>>,
        onResult: (Result<Int>) -> Unit,
    ) {
        viewModelScope.launch {
            onResult(
                telegramProtocol()?.importContacts(accountId, entries)
                    ?: Result.failure(IllegalStateException("Telegram non disponible")),
            )
        }
    }

    fun signalProtocol(): ltechnologies.onionphone.securemessenger.protocol.signal.SignalProtocol? =
        connectionManager.protocolFor(ProtocolId.SIGNAL) as? ltechnologies.onionphone.securemessenger.protocol.signal.SignalProtocol

    fun connectSignal(phone: String, onResult: (ConnectionResult, String) -> Unit) {
        val accountId = UUID.randomUUID().toString()
        val credentials = AccountCredentials(
            protocol = ProtocolId.SIGNAL,
            accountId = accountId,
            displayName = phone,
            secrets = mapOf(
                "e164" to phone.trim(),
                "phone" to phone.trim(),
            ),
        )
        connectAccount(credentials) { result -> onResult(result, accountId) }
    }

    fun cancelSignalLogin(accountId: String) {
        viewModelScope.launch {
            connectionManager.cancelSignalLogin(accountId)
        }
    }

    fun resendSignalCode(onResult: (ConnectionResult) -> Unit) {
        viewModelScope.launch {
            val protocol = connectionManager.protocolFor(ProtocolId.SIGNAL) as? ltechnologies.onionphone.securemessenger.protocol.signal.SignalProtocol
            onResult(protocol?.resendSmsCode() ?: ConnectionResult.Failure("Signal non disponible"))
        }
    }

    fun requestSignalSms(onResult: (ConnectionResult) -> Unit) {
        viewModelScope.launch {
            val protocol = connectionManager.protocolFor(ProtocolId.SIGNAL) as? ltechnologies.onionphone.securemessenger.protocol.signal.SignalProtocol
            onResult(protocol?.requestSmsAfterCaptcha() ?: ConnectionResult.Failure("Signal non disponible"))
        }
    }

    fun connectTelegram(phone: String, onResult: (ConnectionResult, String) -> Unit) {
        val accountId = UUID.randomUUID().toString()
        val credentials = AccountCredentials(
            protocol = ProtocolId.TELEGRAM,
            accountId = accountId,
            displayName = phone,
            secrets = mapOf("phone" to phone.trim()),
        )
        connectAccount(credentials) { result -> onResult(result, accountId) }
    }

    fun connectAccount(credentials: AccountCredentials, onResult: (ConnectionResult) -> Unit) {
        viewModelScope.launch {
            onResult(connectionManager.connect(credentials))
        }
    }

    suspend fun detectEmailSettings(email: String) =
        (connectionManager.protocolFor(ProtocolId.EMAIL)
            as? ltechnologies.onionphone.securemessenger.protocol.email.EmailProtocol)
            ?.detectSettings(email)

    fun registerAccount(request: RegistrationRequest, onResult: (RegistrationResult) -> Unit) {
        viewModelScope.launch {
            onResult(connectionManager.register(request))
        }
    }

    fun continueRegistration(
        protocol: ProtocolId,
        sessionId: String,
        fields: Map<String, String>,
        onResult: (RegistrationResult) -> Unit,
    ) {
        viewModelScope.launch {
            onResult(connectionManager.continueRegistration(protocol, sessionId, fields))
        }
    }

    fun sendMessage(conversationId: String, protocol: ProtocolId, body: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val sanitized = MessageSanitizer.sanitize(body)
            val protocolImpl = connectionManager.protocolFor(protocol)
            if (protocolImpl == null) {
                onResult(false)
                return@launch
            }
            val result = protocolImpl.sendMessage(conversationId, sanitized)
            onResult(result is ltechnologies.onionphone.securemessenger.core.model.SendResult.Success)
        }
    }

    fun sendMedia(
        conversationId: String,
        protocol: ProtocolId,
        attachment: Attachment,
        caption: String?,
        onResult: (Boolean) -> Unit,
    ) {
        viewModelScope.launch {
            val protocolImpl = connectionManager.protocolFor(protocol) ?: run {
                onResult(false)
                return@launch
            }
            val sanitizedCaption = caption?.let { MessageSanitizer.sanitize(it) }
            val result = protocolImpl.sendMedia(conversationId, attachment, sanitizedCaption)
            onResult(result is ltechnologies.onionphone.securemessenger.core.model.SendResult.Success)
        }
    }

    fun updateProxy(config: ProxyConfig) {
        viewModelScope.launch {
            connectionManager.saveProxySettings(config)
        }
    }

    fun testProxy(onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            proxyManager.refreshStatusAndWait()
            onResult(proxyManager.isNetworkAllowed())
        }
    }

    fun requestTorStart() {
        viewModelScope.launch {
            proxyManager.requestTorStart()
        }
    }

    fun continueAuth(protocol: ProtocolId, fields: Map<String, String>, onResult: (ConnectionResult) -> Unit) {
        viewModelScope.launch {
            val impl = connectionManager.protocolFor(protocol) ?: run {
                onResult(ConnectionResult.Failure("Protocol not found"))
                return@launch
            }
            onResult(impl.continueAuthentication(fields))
        }
    }

    fun pendingAuth(protocol: ProtocolId, onResult: (ltechnologies.onionphone.securemessenger.core.model.AuthStep?) -> Unit) {
        viewModelScope.launch {
            onResult(connectionManager.protocolFor(protocol)?.pendingAuthStep())
        }
    }

    fun waitForTelegramAuthStep(
        timeoutMs: Long = 30_000,
        onResult: (ltechnologies.onionphone.securemessenger.core.model.AuthStep?) -> Unit,
    ) {
        viewModelScope.launch {
            val deadline = System.currentTimeMillis() + timeoutMs
            var step: ltechnologies.onionphone.securemessenger.core.model.AuthStep? = null
            while (System.currentTimeMillis() < deadline) {
                step = connectionManager.protocolFor(ProtocolId.TELEGRAM)?.pendingAuthStep()
                if (step != null) break
                if (connectionManager.protocolFor(ProtocolId.TELEGRAM)
                        ?.connectionState?.value == ConnectionState.CONNECTED
                ) {
                    break
                }
                delay(300)
            }
            onResult(step)
        }
    }

    fun resendTelegramCode(onResult: (ConnectionResult) -> Unit) {
        viewModelScope.launch {
            val protocol = connectionManager.protocolFor(ProtocolId.TELEGRAM)
            val result = if (protocol is ltechnologies.onionphone.securemessenger.protocol.telegram.TelegramProtocol) {
                protocol.resendAuthenticationCode()
            } else {
                ConnectionResult.Failure("Telegram non disponible")
            }
            onResult(result)
        }
    }

    fun cancelTelegramLogin(accountId: String) {
        viewModelScope.launch {
            connectionManager.cancelTelegramLogin(accountId)
        }
    }

    fun startSignalDeviceLink(
        deviceName: String = "SecureMessenger",
        onResult: (ConnectionResult) -> Unit,
    ) {
        viewModelScope.launch {
            val proxy = proxyManager.currentConfig().let { config ->
                if (config.torRequired && !proxyManager.ensureProxyReady()) {
                    onResult(ConnectionResult.Failure(
                        "Tor activé mais OnionVPN indisponible — démarrez le tunnel ou désactivez Tor.",
                    ))
                    return@launch
                }
                if (config.torRequired) {
                    config.copy(
                        host = SocksEndpointResolver.resolveReachableHost(config.host, config.port),
                    )
                } else {
                    config
                }
            }
            val protocol = connectionManager.protocolFor(ProtocolId.SIGNAL) as?
                ltechnologies.onionphone.securemessenger.protocol.signal.SignalProtocol
            if (protocol == null) {
                onResult(ConnectionResult.Failure("Signal non disponible"))
                return@launch
            }
            onResult(protocol.startDeviceLink(deviceName, proxy))
        }
    }

    fun cancelSignalDeviceLink() {
        viewModelScope.launch {
            (connectionManager.protocolFor(ProtocolId.SIGNAL)
                as? ltechnologies.onionphone.securemessenger.protocol.signal.SignalProtocol)
                ?.cancelDeviceLink()
        }
    }

    fun observeSignalDeviceLinkUrl(): StateFlow<String?> {
        val protocol = connectionManager.protocolFor(ProtocolId.SIGNAL)
            as? ltechnologies.onionphone.securemessenger.protocol.signal.SignalProtocol
        return protocol?.observeDeviceLinkUrl()
            ?: MutableStateFlow(null)
    }

    fun disconnectAccount(accountId: String, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            connectionManager.disconnectAccount(accountId)
            onResult(true)
        }
    }

    fun restoreSessions() {
        viewModelScope.launch {
            connectionManager.restorePersistedAccounts()
        }
    }

    fun capabilitiesFor(protocol: ProtocolId): ProtocolCapabilities =
        connectionManager.protocolFor(protocol)?.capabilities
            ?: ProtocolCapabilities()

    fun canRegister(protocol: ProtocolId): Boolean =
        connectionManager.protocolFor(protocol)?.canRegister == true

    fun startConversation(
        protocol: ProtocolId,
        remoteId: String,
        message: String?,
        accountId: String? = null,
        asGroup: Boolean = false,
        onResult: (String?) -> Unit,
    ) {
        viewModelScope.launch {
            val resolvedAccountId = accountId
                ?: accounts.value.firstOrNull { it.protocol == protocol }?.id
            if (resolvedAccountId == null) {
                onResult(null)
                return@launch
            }
            val impl = connectionManager.protocolFor(protocol) ?: run {
                onResult(null)
                return@launch
            }
            val sanitized = message?.let { MessageSanitizer.sanitize(it) }
            when (val result = impl.startConversation(remoteId, sanitized, resolvedAccountId, asGroup)) {
                is ltechnologies.onionphone.securemessenger.core.model.SendResult.Success ->
                    // Protocols return conversation id here (not message id).
                    onResult(result.messageId)
                is ltechnologies.onionphone.securemessenger.core.model.SendResult.Failure ->
                    onResult(null)
            }
        }
    }

    fun openOnionVpnReleases() {
        onionVpnHelper.openAppOrReleases()
    }

    /** Reachable SOCKS host/port for the current proxy — used by the registration WebView fallback. */
    fun resolvedSocksEndpoint(): Pair<String, Int> {
        val config = proxyManager.currentConfig()
        return SocksEndpointResolver.resolveReachableHost(config.host, config.port) to config.port
    }
}
