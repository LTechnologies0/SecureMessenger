package ltechnologies.onionphone.securemessenger.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import ltechnologies.onionphone.securemessenger.core.model.AccountCredentials
import ltechnologies.onionphone.securemessenger.core.model.ConnectionResult
import ltechnologies.onionphone.securemessenger.core.model.ConnectionState
import ltechnologies.onionphone.securemessenger.core.model.Attachment
import ltechnologies.onionphone.securemessenger.core.model.Conversation
import ltechnologies.onionphone.securemessenger.core.model.FeatureFlags
import ltechnologies.onionphone.securemessenger.core.model.HistoryLoadResult
import ltechnologies.onionphone.securemessenger.core.model.Message
import ltechnologies.onionphone.securemessenger.core.model.ProtocolId
import ltechnologies.onionphone.securemessenger.core.model.ProxyConfig
import ltechnologies.onionphone.securemessenger.core.model.RegistrationRequest
import ltechnologies.onionphone.securemessenger.core.model.RegistrationResult
import ltechnologies.onionphone.securemessenger.core.model.SanitizedText
import ltechnologies.onionphone.securemessenger.core.proxy.InvizibleHelper
import ltechnologies.onionphone.securemessenger.core.proxy.ProxyManager
import ltechnologies.onionphone.securemessenger.core.proxy.ProxyStatus
import ltechnologies.onionphone.securemessenger.core.proxy.SocksEndpointResolver
import ltechnologies.onionphone.securemessenger.core.security.MessageSanitizer
import ltechnologies.onionphone.securemessenger.data.MessengerRepository
import ltechnologies.onionphone.securemessenger.protocol.telegram.TelegramProtocol
import ltechnologies.onionphone.securemessenger.service.ConnectionManager

@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: MessengerRepository,
    private val connectionManager: ConnectionManager,
    private val proxyManager: ProxyManager,
    private val invizibleHelper: InvizibleHelper,
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

    val enabledProtocols: List<ProtocolId> = FeatureFlags.enabled.toList()

    fun messagesFor(conversationId: String): StateFlow<List<Message>> =
        messageFlows.getOrPut(conversationId) {
            repository.observeMessages(conversationId)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
        }

    suspend fun loadMessageHistory(conversationId: String, protocol: ProtocolId): HistoryLoadResult =
        connectionManager.protocolFor(protocol)?.loadMessageHistory(conversationId)
            ?: HistoryLoadResult.Failure("Protocole indisponible")

    fun closeConversation(conversationId: String, protocol: ProtocolId) {
        viewModelScope.launch {
            connectionManager.protocolFor(protocol)?.closeConversation(conversationId)
        }
    }

    fun telegramProtocol(): TelegramProtocol? =
        connectionManager.protocolFor(ProtocolId.TELEGRAM) as? TelegramProtocol

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

    fun requestOrbot() {
        viewModelScope.launch {
            proxyManager.requestOrbotStart()
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

    fun startConversation(
        protocol: ProtocolId,
        remoteId: String,
        message: String?,
        accountId: String? = null,
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
            val result = impl.startConversation(remoteId, sanitized, resolvedAccountId)
            val convId = "${resolvedAccountId}_$remoteId"
            onResult(if (result is ltechnologies.onionphone.securemessenger.core.model.SendResult.Success) convId else null)
        }
    }

    fun openInvizibleStore() {
        invizibleHelper.openStoreListing()
    }

    /** Reachable SOCKS host/port for the current proxy — used by the registration WebView fallback. */
    fun resolvedSocksEndpoint(): Pair<String, Int> {
        val config = proxyManager.currentConfig()
        return SocksEndpointResolver.resolveReachableHost(config.host, config.port) to config.port
    }
}
