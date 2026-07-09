package ltechnologies.onionphone.securemessenger.service

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ltechnologies.onionphone.securemessenger.core.model.AccountCredentials
import ltechnologies.onionphone.securemessenger.core.model.ConnectionResult
import ltechnologies.onionphone.securemessenger.core.model.ConnectionState
import ltechnologies.onionphone.securemessenger.core.model.FeatureFlags
import ltechnologies.onionphone.securemessenger.core.model.ProtocolId
import ltechnologies.onionphone.securemessenger.core.model.ProxyConfig
import ltechnologies.onionphone.securemessenger.core.model.RegistrationRequest
import ltechnologies.onionphone.securemessenger.core.model.RegistrationResult
import ltechnologies.onionphone.securemessenger.core.proxy.ProxyConfigNormalizer
import ltechnologies.onionphone.securemessenger.core.proxy.ProxyManager
import ltechnologies.onionphone.securemessenger.core.proxy.SocksEndpointResolver
import ltechnologies.onionphone.securemessenger.core.security.EncryptedCredentialStore
import ltechnologies.onionphone.securemessenger.data.MessengerRepository
import ltechnologies.onionphone.securemessenger.protocol.api.ProtocolNotEnabledException
import ltechnologies.onionphone.securemessenger.protocol.api.ProtocolRegistry
import ltechnologies.onionphone.securemessenger.protocol.telegram.TelegramProtocol
import timber.log.Timber

@Singleton
class ConnectionManager @Inject constructor(
    private val protocolRegistry: ProtocolRegistry,
    private val proxyManager: ProxyManager,
    private val repository: MessengerRepository,
    private val credentialStore: EncryptedCredentialStore,
) {
    private val bootstrapScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bootstrapReady = CompletableDeferred<Unit>()
    private val restoreMutex = Mutex()

    private var lastProxyHealthy: Boolean? = null
    private var lastProxyConfig: ProxyConfig? = null

    init {
        bootstrapScope.launch {
            runCatching {
                val saved = repository.observeProxySettings().first()
                val password = credentialStore.getProxyPassword()
                if (saved != null) {
                    val merged = ProxyConfigNormalizer.normalize(
                        saved.copy(password = password),
                    )
                    proxyManager.updateConfig(merged)
                }
                proxyManager.refreshStatusAndWait()
            }.onFailure { Timber.w(it, "Proxy bootstrap failed") }
            bootstrapReady.complete(Unit)
            restorePersistedAccounts()
        }
    }

    private val _killswitchActive = MutableStateFlow(false)
    val killswitchActive: StateFlow<Boolean> = _killswitchActive.asStateFlow()

    private suspend fun awaitBootstrap() {
        bootstrapReady.await()
    }

    /** Resolves the current proxy (with the reachable SOCKS host) or activates the killswitch and returns null. */
    private suspend fun resolveProxyOrFail(): ProxyConfig? {
        if (!proxyManager.ensureProxyReady()) {
            _killswitchActive.value = true
            return null
        }
        _killswitchActive.value = false
        val rawProxy = proxyManager.currentConfig()
        return rawProxy.copy(
            host = SocksEndpointResolver.resolveReachableHost(rawProxy.host, rawProxy.port),
        )
    }

    private val proxyRequiredMessage =
        "Tor requis : démarrez Orbot ou InviZible (Paramètres → Proxy), puis réessayez."

    suspend fun connect(credentials: AccountCredentials): ConnectionResult {
        awaitBootstrap()
        if (credentials.protocol !in FeatureFlags.enabled) {
            return ConnectionResult.Failure(
                ProtocolNotEnabledException(credentials.protocol).message ?: "Disabled",
            )
        }
        val proxy = resolveProxyOrFail() ?: return ConnectionResult.Failure(proxyRequiredMessage)

        credentials.secrets.forEach { (key, value) ->
            credentialStore.put(credentials.accountId, key, value)
        }
        credentialStore.putAccountMeta(
            credentials.accountId,
            credentials.protocol.name,
            credentials.displayName,
        )

        val protocol = protocolRegistry.get(credentials.protocol)
            ?: return ConnectionResult.Failure("Protocol not found")

        val result = protocol.connect(credentials, proxy)
        if (result is ConnectionResult.Success) {
            val pending = protocol.pendingAuthStep()
            val telegramConnecting = credentials.protocol == ProtocolId.TELEGRAM &&
                protocol.connectionState.value != ConnectionState.CONNECTED
            val state = when {
                pending != null -> ConnectionState.CONNECTING
                telegramConnecting -> ConnectionState.CONNECTING
                else -> ConnectionState.CONNECTED
            }
            val shouldPersistAccount = credentials.protocol != ProtocolId.TELEGRAM ||
                state == ConnectionState.CONNECTED
            if (shouldPersistAccount) {
                repository.upsertAccount(
                    ltechnologies.onionphone.securemessenger.core.model.Account(
                        id = credentials.accountId,
                        protocol = credentials.protocol,
                        displayName = credentials.displayName,
                        connectionState = state,
                    ),
                )
            }
        }
        return result
    }

    suspend fun register(request: RegistrationRequest): RegistrationResult {
        awaitBootstrap()
        if (request.protocol !in FeatureFlags.enabled) {
            return RegistrationResult.Failure(
                ProtocolNotEnabledException(request.protocol).message ?: "Disabled",
            )
        }
        val proxy = resolveProxyOrFail() ?: return RegistrationResult.Failure(proxyRequiredMessage)
        val protocol = protocolRegistry.get(request.protocol)
            ?: return RegistrationResult.Failure("Protocol not found")

        val result = protocol.register(request, proxy)
        if (result is RegistrationResult.Success) {
            // Persist secrets/account row and establish the real authenticated session by
            // reusing the normal login path — the registration classes only create the account,
            // they don't keep a live connection around.
            connect(result.credentials)
        }
        return result
    }

    suspend fun continueRegistration(
        protocolId: ProtocolId,
        sessionId: String,
        fields: Map<String, String>,
    ): RegistrationResult {
        awaitBootstrap()
        val proxy = resolveProxyOrFail() ?: return RegistrationResult.Failure(proxyRequiredMessage)
        val protocol = protocolRegistry.get(protocolId)
            ?: return RegistrationResult.Failure("Protocol not found")

        val result = protocol.continueRegistration(sessionId, fields, proxy)
        if (result is RegistrationResult.Success) {
            connect(result.credentials)
        }
        return result
    }

    suspend fun restorePersistedAccounts() {
        awaitBootstrap()
        restoreMutex.withLock {
            if (!proxyManager.ensureProxyReady()) {
                Timber.i("Skip account restore: proxy not ready")
                _killswitchActive.value = true
                return
            }
            _killswitchActive.value = false

            val roomAccounts = repository.observeAccounts().first()
            for (account in roomAccounts) {
                if (account.protocol == ProtocolId.TELEGRAM &&
                    account.connectionState != ConnectionState.CONNECTED
                ) {
                    Timber.i("Removing incomplete Telegram account ${account.id}")
                    credentialStore.removeAccount(account.id)
                    repository.deleteAccount(account.id)
                }
            }
            val cleanedRoomAccounts = repository.observeAccounts().first()
            val ids = (cleanedRoomAccounts.map { it.id } + credentialStore.listAccountIds()).toSet()
            for (accountId in ids) {
                val protocolName = credentialStore.getProtocol(accountId)
                    ?: cleanedRoomAccounts.firstOrNull { it.id == accountId }?.protocol?.name
                    ?: continue
                val protocolId = runCatching { ProtocolId.valueOf(protocolName) }.getOrNull() ?: continue
                if (protocolId !in FeatureFlags.enabled) continue

                val existing = cleanedRoomAccounts.firstOrNull { it.id == accountId }
                if (protocolId == ProtocolId.TELEGRAM && existing?.connectionState != ConnectionState.CONNECTED) {
                    credentialStore.removeAccount(accountId)
                    continue
                }

                val secrets = credentialStore.getAllForAccount(accountId)
                    .filterKeys { !it.startsWith("__") }
                if (secrets.isEmpty()) continue

                val displayName = credentialStore.getDisplayName(accountId)
                    ?: existing?.displayName
                    ?: accountId

                val creds = AccountCredentials(
                    protocol = protocolId,
                    accountId = accountId,
                    displayName = displayName,
                    secrets = secrets,
                )
                Timber.i("Restoring account $accountId ($protocolId)")
                connect(creds)
            }
        }
    }

    suspend fun cancelTelegramLogin(accountId: String) {
        protocolRegistry.get(ProtocolId.TELEGRAM)?.disconnect()
        credentialStore.removeAccount(accountId)
        repository.deleteAccount(accountId)
    }

    suspend fun disconnectAccount(accountId: String) {
        val protocolName = credentialStore.getProtocol(accountId)
        val protocolId = protocolName?.let { runCatching { ProtocolId.valueOf(it) }.getOrNull() }
        protocolId?.let { protocolRegistry.get(it)?.disconnect() }
        credentialStore.removeAccount(accountId)
        repository.deleteAccount(accountId)
    }

    suspend fun disconnect(protocolId: ProtocolId) {
        protocolRegistry.get(protocolId)?.disconnect()
    }

    suspend fun disconnectAll() {
        FeatureFlags.enabled.forEach { id ->
            try {
                protocolRegistry.get(id)?.disconnect()
            } catch (e: Exception) {
                Timber.w(e, "Disconnect failed for $id")
            }
        }
    }

    fun protocolFor(id: ProtocolId) = protocolRegistry.get(id)

    suspend fun onProxyStateChanged(healthy: Boolean, config: ProxyConfig) {
        val wasHealthy = lastProxyHealthy
        val previousConfig = lastProxyConfig
        lastProxyHealthy = healthy
        lastProxyConfig = config

        _killswitchActive.value = !healthy

        when {
            !healthy -> disconnectAll()
            wasHealthy == false && healthy -> restorePersistedAccounts()
            previousConfig != null && previousConfig != config -> {
                Timber.i("Proxy config changed — reconnecting through ${config.host}:${config.port}")
                disconnectAll()
                reapplyTelegramProxy(config)
                restorePersistedAccounts()
            }
        }
    }

    private suspend fun reapplyTelegramProxy(config: ProxyConfig) {
        val telegram = protocolRegistry.get(ProtocolId.TELEGRAM) as? TelegramProtocol ?: return
        if (telegram.connectionState.value != ConnectionState.CONNECTED) return
        telegram.tdLibFacade()?.configureProxy(
            config.host,
            config.port,
            config.username,
            config.password,
        )
    }

    suspend fun refreshProxyState() {
        proxyManager.refreshStatusAndWait()
        onProxyStateChanged(proxyManager.isNetworkAllowed(), proxyManager.currentConfig())
    }

    suspend fun saveProxySettings(config: ProxyConfig) {
        awaitBootstrap()
        val normalized = ProxyConfigNormalizer.normalize(config)
        val previous = proxyManager.currentConfig()
        repository.saveProxySettings(normalized)
        credentialStore.putProxyPassword(normalized.password)
        val withSecrets = normalized.copy(
            password = normalized.password ?: credentialStore.getProxyPassword(),
        )
        proxyManager.updateConfig(withSecrets)
        proxyManager.refreshStatusAndWait()
        if (previous.host != withSecrets.host ||
            previous.port != withSecrets.port ||
            previous.torProvider != withSecrets.torProvider ||
            previous.username != withSecrets.username ||
            previous.password != withSecrets.password
        ) {
            onProxyStateChanged(proxyManager.isNetworkAllowed(), proxyManager.currentConfig())
        }
    }
}
