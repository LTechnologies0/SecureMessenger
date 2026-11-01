package ltechnologies.onionphone.securemessenger.service

import dagger.Lazy
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
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
import ltechnologies.onionphone.securemessenger.core.security.AppLockManager
import ltechnologies.onionphone.securemessenger.core.security.AppLockState
import ltechnologies.onionphone.securemessenger.core.security.EncryptedCredentialStore
import ltechnologies.onionphone.securemessenger.data.EncryptedMessengerDatabase
import ltechnologies.onionphone.securemessenger.data.MessengerRepository
import ltechnologies.onionphone.securemessenger.protocol.api.ProtocolNotEnabledException
import ltechnologies.onionphone.securemessenger.protocol.api.ProtocolRegistry
import ltechnologies.onionphone.securemessenger.protocol.telegram.TelegramProtocol
import ltechnologies.onionphone.securemessenger.protocol.signal.SignalProtocol
import timber.log.Timber

@Singleton
class ConnectionManager @Inject constructor(
    private val protocolRegistry: ProtocolRegistry,
    private val proxyManager: ProxyManager,
    private val repository: Lazy<MessengerRepository>,
    private val encryptedDb: EncryptedMessengerDatabase,
    private val credentialStore: EncryptedCredentialStore,
    private val appLockManager: AppLockManager,
) {
    private val bootstrapScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bootstrapReady = CompletableDeferred<Unit>()
    private val restoreMutex = Mutex()
    /** Incremented on app lock — in-flight restore/connect batches bail out. */
    @Volatile
    private var restoreEpoch = 0
    private var proxyBootstrapped = false

    private var lastProxyHealthy: Boolean? = null
    private var lastProxyConfig: ProxyConfig? = null
    private var killswitchDebounceJob: Job? = null

    init {
        bootstrapScope.launch {
            appLockManager.state.collect { state ->
                when (state) {
                    AppLockState.LOCKED, AppLockState.DEVICE_INSECURE -> {
                        restoreEpoch++
                        killswitchDebounceJob?.cancel()
                        killswitchDebounceJob = null
                        runCatching { disconnectAll() }
                        // Drop SQLCipher handle so DAO flows cannot keep reading after lock.
                        runCatching { encryptedDb.close() }
                    }
                    AppLockState.UNLOCKED -> {
                        runCatching { ensureProxyBootstrapped() }
                            .onFailure { Timber.w(it, "Proxy bootstrap failed") }
                        restorePersistedAccounts()
                    }
                }
            }
        }
    }

    private suspend fun ensureProxyBootstrapped() {
        if (proxyBootstrapped) {
            completeBootstrap()
            return
        }
        try {
            val saved = repository.get().observeProxySettings().first()
            val password = credentialStore.getProxyPassword()
            if (saved != null) {
                val merged = ProxyConfigNormalizer.normalize(
                    saved.copy(password = password),
                )
                proxyManager.updateConfig(merged)
            }
            proxyManager.refreshStatusAndWait()
            proxyBootstrapped = true
        } finally {
            // Always unblock connect()/register() even if Tor/DB bootstrap fails.
            completeBootstrap()
        }
    }

    private fun completeBootstrap() {
        if (!bootstrapReady.isCompleted) {
            bootstrapReady.complete(Unit)
        }
    }

    private val _killswitchActive = MutableStateFlow(false)
    val killswitchActive: StateFlow<Boolean> = _killswitchActive.asStateFlow()

    private suspend fun awaitBootstrap() {
        appLockManager.awaitUnlocked()
        if (!bootstrapReady.isCompleted) {
            ensureProxyBootstrapped()
        }
        bootstrapReady.await()
    }

    /**
     * Resolves proxy settings for a connection.
     * Tor is mandatory: a healthy OnionVPN/custom SOCKS endpoint is required.
     * Arms [_killswitchActive] when SOCKS is down.
     */
    @Suppress("UNUSED_PARAMETER")
    private suspend fun resolveProxyOrFail(protocolId: ProtocolId? = null): ProxyConfig? {
        val rawProxy = proxyManager.currentConfig()
        if (!proxyManager.ensureProxyReady()) {
            _killswitchActive.value = true
            return null
        }
        _killswitchActive.value = false
        return rawProxy.copy(
            host = SocksEndpointResolver.resolveReachableHost(rawProxy.host, rawProxy.port),
            torRequired = true,
        )
    }

    private val proxyRequiredMessage =
        "Tor requis : proxy SOCKS indisponible — démarrez OnionVPN (Paramètres → Proxy)."

    suspend fun connect(credentials: AccountCredentials): ConnectionResult {
        awaitBootstrap()
        if (credentials.protocol !in FeatureFlags.enabled) {
            return ConnectionResult.Failure(
                ProtocolNotEnabledException(credentials.protocol).message ?: "Disabled",
            )
        }
        val proxy = resolveProxyOrFail(credentials.protocol) ?: return ConnectionResult.Failure(proxyRequiredMessage)

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
        val state = when (result) {
            is ConnectionResult.Success -> {
                val pending = protocol.pendingAuthStep()
                // Require explicit accountId match — unscoped pending must not freeze this account.
                val pendingForThis = pending != null && pending.accountId == credentials.accountId
                when {
                    pendingForThis -> ConnectionState.CONNECTING
                    protocol.isAccountConnected(credentials.accountId) -> ConnectionState.CONNECTED
                    else -> ConnectionState.CONNECTING
                }
            }
            is ConnectionResult.Failure -> ConnectionState.ERROR
        }
        if (result is ConnectionResult.Success && state == ConnectionState.CONNECTED) {
            credentialStore.put(credentials.accountId, ACCOUNT_ESTABLISHED_KEY, "true")
        }
        if (result is ConnectionResult.Failure &&
            !isAccountEstablished(credentials.accountId) &&
            protocol.pendingAuthStep()?.accountId != credentials.accountId
        ) {
            // Failed first-time add must not leave secrets that restore() will redial forever
            // (seen as phantom IRC/XMPP/… connections on every app launch).
            Timber.i(
                "Dropping never-established account ${credentials.accountId} after connect failure",
            )
            credentialStore.removeAccount(credentials.accountId)
            runCatching { repository.get().deleteAccount(credentials.accountId) }
            return result
        }
        repository.get().upsertAccount(
            ltechnologies.onionphone.securemessenger.core.model.Account(
                id = credentials.accountId,
                protocol = credentials.protocol,
                displayName = credentials.displayName,
                connectionState = state,
            ),
        )
        return result
    }

    suspend fun register(request: RegistrationRequest): RegistrationResult {
        awaitBootstrap()
        if (request.protocol !in FeatureFlags.enabled) {
            return RegistrationResult.Failure(
                ProtocolNotEnabledException(request.protocol).message ?: "Disabled",
            )
        }
        val proxy = resolveProxyOrFail(request.protocol) ?: return RegistrationResult.Failure(proxyRequiredMessage)
        val protocol = protocolRegistry.get(request.protocol)
            ?: return RegistrationResult.Failure("Protocol not found")

        val result = protocol.register(request, proxy)
        if (result is RegistrationResult.Success) {
            // Persist secrets/account row and establish the real authenticated session by
            // reusing the normal login path — the registration classes only create the account,
            // they don't keep a live connection around.
            val connectResult = connect(result.credentials)
            if (connectResult is ConnectionResult.Failure) {
                return RegistrationResult.Failure(
                    "Compte créé mais connexion échouée : ${connectResult.reason}",
                )
            }
        }
        return result
    }

    suspend fun continueRegistration(
        protocolId: ProtocolId,
        sessionId: String,
        fields: Map<String, String>,
    ): RegistrationResult {
        awaitBootstrap()
        val proxy = resolveProxyOrFail(protocolId) ?: return RegistrationResult.Failure(proxyRequiredMessage)
        val protocol = protocolRegistry.get(protocolId)
            ?: return RegistrationResult.Failure("Protocol not found")

        val result = protocol.continueRegistration(sessionId, fields, proxy)
        if (result is RegistrationResult.Success) {
            val connectResult = connect(result.credentials)
            if (connectResult is ConnectionResult.Failure) {
                return RegistrationResult.Failure(
                    "Compte créé mais connexion échouée : ${connectResult.reason}",
                )
            }
        }
        return result
    }

    suspend fun restorePersistedAccounts() {
        if (!appLockManager.isUnlocked) return
        awaitBootstrap()
        // Re-check after bootstrap — user may have backgrounded and locked mid-await.
        if (!appLockManager.isUnlocked) return
        restoreMutex.withLock {
            if (!appLockManager.isUnlocked) return
            try {
                restorePersistedAccountsLocked()
            } catch (e: IllegalStateException) {
                if (!appLockManager.isUnlocked) {
                    Timber.i("Restore aborted — app locked during DB access")
                    return
                }
                throw e
            }
        }
    }

    private suspend fun restorePersistedAccountsLocked() {
            val epoch = restoreEpoch
            val config = proxyManager.currentConfig()
            proxyManager.refreshStatusAndWait()
            if (!appLockManager.isUnlocked) return
            val socksUp = proxyManager.isNetworkAllowed()
            _killswitchActive.value = !socksUp

            val roomAccounts = repository.get().observeAccounts().first()
            val telegram = protocolRegistry.get(ProtocolId.TELEGRAM)
                as? ltechnologies.onionphone.securemessenger.protocol.telegram.TelegramProtocol
            for (account in roomAccounts) {
                val pending = runCatching {
                    protocolRegistry.get(account.protocol)?.pendingAuthStep()
                }.getOrNull()
                val pendingForThis = pending != null && pending.accountId == account.id
                if (pendingForThis) {
                    Timber.i("Keeping mid-auth account ${account.id} (${account.protocol})")
                    continue
                }
                if (account.protocol == ProtocolId.TELEGRAM &&
                    account.connectionState != ConnectionState.CONNECTED
                ) {
                    // Keep if TDLib session is live OR provisioned secrets exist (soft-disconnect).
                    if (telegram?.hasLiveSession(account.id) == true ||
                        hasTelegramProvisionedSecrets(account.id)
                    ) {
                        Timber.i("Keeping Telegram account ${account.id} for restore")
                    } else {
                        Timber.i("Removing incomplete Telegram account ${account.id}")
                        credentialStore.removeAccount(account.id)
                        repository.get().deleteAccount(account.id)
                    }
                }
                if (account.protocol == ProtocolId.SIGNAL &&
                    account.connectionState != ConnectionState.CONNECTED
                ) {
                    // Official Signal keeps the secondary through link-and-sync (up to ~1h).
                    // SESSION_READY+ACI means provision finished — only wait for archive/sync.
                    if (isSignalProvisioned(account.id)) {
                        Timber.i(
                            "Keeping Signal account ${account.id} while CONNECTING (link-and-sync)",
                        )
                    } else {
                        Timber.i("Removing incomplete Signal account ${account.id}")
                        credentialStore.removeAccount(account.id)
                        repository.get().deleteAccount(account.id)
                    }
                }
            }
            val cleanedRoomAccounts = repository.get().observeAccounts().first()
            val ids = (cleanedRoomAccounts.map { it.id } + credentialStore.listAccountIds()).toSet()
            // Bounded parallel restore: a slow Signal/Matrix/Telegram connect must not block
            // every other account after unlock or proxy recovery.
            val restoreGate = Semaphore(permits = 3)
            coroutineScope {
                ids.map { accountId ->
                    async {
                        restoreGate.withPermit {
                            if (epoch != restoreEpoch || !appLockManager.isUnlocked) return@withPermit
                            restoreOneAccount(accountId, cleanedRoomAccounts, config, epoch)
                        }
                    }
                }.awaitAll()
            }
    }

    private suspend fun restoreOneAccount(
        accountId: String,
        cleanedRoomAccounts: List<ltechnologies.onionphone.securemessenger.core.model.Account>,
        config: ProxyConfig,
        restoreEpochAtStart: Int,
    ) {
                if (restoreEpochAtStart != restoreEpoch || !appLockManager.isUnlocked) return
                val protocolName = credentialStore.getProtocol(accountId)
                    ?: cleanedRoomAccounts.firstOrNull { it.id == accountId }?.protocol?.name
                    ?: return
                val protocolId = runCatching { ProtocolId.valueOf(protocolName) }.getOrNull() ?: return
                if (protocolId !in FeatureFlags.enabled) return

                val existing = cleanedRoomAccounts.firstOrNull { it.id == accountId }
                val pending = runCatching { protocolRegistry.get(protocolId)?.pendingAuthStep() }.getOrNull()
                val pendingForThis = pending != null && pending.accountId == accountId
                if (pendingForThis) {
                    Timber.i("Skip restore for $accountId — pendingAuthStep")
                    return
                }
                if (!proxyManager.isNetworkAllowed()) {
                    Timber.i("Skip restore for $accountId ($protocolId): Tor required but SOCKS down")
                    return
                }
                // Migrate pre-marker installs that last left the row CONNECTED.
                if (existing?.connectionState == ConnectionState.CONNECTED &&
                    !isAccountEstablished(accountId)
                ) {
                    credentialStore.put(accountId, ACCOUNT_ESTABLISHED_KEY, "true")
                }
                // Ghost cleanup: secrets persisted on a failed first connect (pre-fix) or a
                // half-written row — never successfully CONNECTED. Do not redial them.
                if (!isAccountEstablished(accountId) &&
                    existing?.connectionState != ConnectionState.CONNECTED &&
                    existing?.connectionState != ConnectionState.CONNECTING &&
                    !(protocolId == ProtocolId.SIGNAL && isSignalProvisioned(accountId)) &&
                    !(protocolId == ProtocolId.TELEGRAM && hasTelegramProvisionedSecrets(accountId))
                ) {
                    Timber.i("Removing never-established account $accountId ($protocolId)")
                    credentialStore.removeAccount(accountId)
                    runCatching { repository.get().deleteAccount(accountId) }
                    return
                }
                if (protocolId == ProtocolId.TELEGRAM && existing?.connectionState != ConnectionState.CONNECTED) {
                    val liveTg = (protocolRegistry.get(ProtocolId.TELEGRAM)
                        as? ltechnologies.onionphone.securemessenger.protocol.telegram.TelegramProtocol)
                        ?.hasLiveSession(accountId) == true
                    if (liveTg || hasTelegramProvisionedSecrets(accountId)) {
                        // Soft-disconnected or mid-auth with secrets — reconnect below.
                    } else {
                        credentialStore.removeAccount(accountId)
                        runCatching { repository.get().deleteAccount(accountId) }
                        return
                    }
                }
                if (protocolId == ProtocolId.SIGNAL && existing?.connectionState != ConnectionState.CONNECTED) {
                    if (!isSignalProvisioned(accountId)) {
                        credentialStore.removeAccount(accountId)
                        runCatching { repository.get().deleteAccount(accountId) }
                        return
                    }
                    // Provisioned but still CONNECTING (link-and-sync) — reconnect to finish.
                }

                // restorePersistedAccounts() runs on every "proxy became healthy" transition, not
                // just app cold-start. A live, already-CONNECTED session shouldn't be torn down
                // and rebuilt just because a routine proxy health probe blipped — XmppProtocol and
                // MatrixProtocol both disconnect the old session before reconnecting, so churning
                // an already-good connection here was silently dropping in-flight messages on
                // every health-check flap. Only (re)connect accounts that actually need it.
                if (existing?.connectionState == ConnectionState.CONNECTED &&
                    protocolRegistry.get(protocolId)?.isAccountConnected(accountId) == true
                ) {
                    return
                }

                val secrets = credentialStore.getAllForAccount(accountId)
                    .filterKeys { !it.startsWith("__") }
                if (secrets.isEmpty()) return

                val displayName = credentialStore.getDisplayName(accountId)
                    ?: existing?.displayName
                    ?: accountId

                val creds = AccountCredentials(
                    protocol = protocolId,
                    accountId = accountId,
                    displayName = displayName,
                    secrets = secrets,
                )
                if (restoreEpochAtStart != restoreEpoch || !appLockManager.isUnlocked) return
                Timber.i("Restoring account $accountId ($protocolId)")
                connect(creds)
    }

    suspend fun cancelTelegramLogin(accountId: String) {
        // Only tear down this login attempt — never all Telegram sessions.
        protocolRegistry.get(ProtocolId.TELEGRAM)?.disconnect(accountId)
        credentialStore.removeAccount(accountId)
        repository.get().deleteAccount(accountId)
    }

    suspend fun cancelSignalLogin(accountId: String) {
        awaitBootstrap()
        (protocolRegistry.get(ProtocolId.SIGNAL) as? SignalProtocol)
            ?.cancelRegistration(forAccountId = accountId)
        credentialStore.removeAccount(accountId)
        repository.get().deleteAccount(accountId)
    }

    suspend fun disconnectAccount(accountId: String) {
        val protocolName = credentialStore.getProtocol(accountId)
        val protocolId = protocolName?.let { runCatching { ProtocolId.valueOf(it) }.getOrNull() }
        // Pass the accountId through so protocols managing multiple simultaneous accounts of the
        // same type (XMPP, Matrix) only tear down this one session, not every connected account.
        protocolId?.let { id ->
            val protocol = protocolRegistry.get(id)
            runCatching { protocol?.disconnect(accountId) }
            runCatching { protocol?.wipeLocalData(accountId) }
        }
        credentialStore.removeAccount(accountId)
        if (appLockManager.isUnlocked) {
            repository.get().deleteAccount(accountId)
        }
    }

    suspend fun disconnect(protocolId: ProtocolId) {
        protocolRegistry.get(protocolId)?.disconnect()
    }

    /**
     * When SOCKS stays down, arm the killswitch and disconnect every session.
     * Only one debounce job runs — prior jobs are cancelled so a stale timer cannot
     * clear killswitch / flip [lastProxyHealthy] after a newer unhealthy edge.
     */
    private fun scheduleDisconnectIfStillUnhealthy() {
        killswitchDebounceJob?.cancel()
        killswitchDebounceJob = bootstrapScope.launch {
            kotlinx.coroutines.delay(3_000)
            proxyManager.refreshStatusAndWait()
            if (!proxyManager.isNetworkAllowed()) {
                _killswitchActive.value = true
                disconnectAllSessions()
            }
            // Do not clear killswitch or rewrite lastProxyHealthy here — that belongs
            // to onProxyStateChanged on a real healthy edge.
        }
    }

    /**
     * Tears down live sessions per accountId so multi-account protocols (XMPP/Matrix/Telegram)
     * update Room rows one-by-one. Protocol-wide [disconnect] is used as a mop-up for orphan
     * mid-auth sessions that never got a Room row.
     */
    private suspend fun disconnectAllSessions() {
        val accounts = runCatching { repository.get().observeAccounts().first() }.getOrDefault(emptyList())
        for (account in accounts) {
            try {
                protocolRegistry.get(account.protocol)?.disconnect(account.id)
            } catch (e: Exception) {
                Timber.w(e, "Disconnect failed for ${account.id}")
            }
        }
        FeatureFlags.enabled.forEach { id ->
            try {
                protocolRegistry.get(id)?.disconnect()
            } catch (e: Exception) {
                Timber.w(e, "Disconnect mop-up failed for $id")
            }
        }
    }

    suspend fun disconnectAll() {
        disconnectAllSessions()
    }

    fun protocolFor(id: ProtocolId) = protocolRegistry.get(id)

    suspend fun onProxyStateChanged(healthy: Boolean, config: ProxyConfig) {
        if (!appLockManager.isUnlocked) return
        val wasHealthy = lastProxyHealthy
        val previousConfig = lastProxyConfig
        lastProxyHealthy = healthy
        lastProxyConfig = config

        _killswitchActive.value = !healthy

        when {
            !healthy -> scheduleDisconnectIfStillUnhealthy()
            // null→true: unlock skipped restore while SOCKS was down; first healthy must restore.
            (wasHealthy == null || wasHealthy == false) && healthy -> {
                killswitchDebounceJob?.cancel()
                killswitchDebounceJob = null
                restorePersistedAccounts()
            }
            previousConfig != null && isMeaningfulConfigChange(previousConfig, config) -> {
                Timber.i("Proxy config changed — reconnecting through ${config.host}:${config.port}")
                killswitchDebounceJob?.cancel()
                killswitchDebounceJob = null
                disconnectAll()
                reapplyTelegramProxy(config)
                restorePersistedAccounts()
            }
        }
    }

    /**
     * True only when a setting that actually requires tearing down live connections changed.
     *
     * [ProxyManager] rewrites [ProxyConfig.host] on every health check to whichever bridge
     * candidate ([SocksEndpointResolver]) answered a TCP probe fastest — that pick can legitimately
     * flip between equally-valid loopback bridges (e.g. "127.0.0.1" vs "192.168.240.1" on Waydroid)
     * from one check to the next without Tor itself changing at all. Reconnecting every account
     * whenever that happens caused a disconnect/reconnect storm approximately every health-check
     * cycle, dropping in-flight XMPP/Matrix messages. Only react when the port, credentials, or
     * provider changed, or when the host changed to something that isn't just another
     * host-bridge candidate for the same target (i.e. a genuine reconfiguration).
     */
    private fun isMeaningfulConfigChange(previous: ProxyConfig, current: ProxyConfig): Boolean {
        if (previous.port != current.port ||
            previous.torProvider != current.torProvider ||
            previous.username != current.username ||
            previous.password != current.password ||
            previous.torRequired != current.torRequired ||
            previous.remoteDns != current.remoteDns
        ) {
            return true
        }
        if (previous.host == current.host) return false
        val bothBridgeCandidates = SocksEndpointResolver.isHostBridgeEndpoint(previous.host) &&
            SocksEndpointResolver.isHostBridgeEndpoint(current.host)
        return !bothBridgeCandidates
    }

    private suspend fun reapplyTelegramProxy(config: ProxyConfig) {
        val telegram = protocolRegistry.get(ProtocolId.TELEGRAM) as? TelegramProtocol ?: return
        // Apply to every live TDLib client — including mid-auth CONNECTING sessions.
        telegram.reapplyProxy(config)
    }

    suspend fun refreshProxyState() {
        proxyManager.refreshStatusAndWait()
        onProxyStateChanged(proxyManager.isNetworkAllowed(), proxyManager.currentConfig())
    }

    suspend fun saveProxySettings(config: ProxyConfig) {
        awaitBootstrap()
        val normalized = ProxyConfigNormalizer.normalize(config)
        val previous = proxyManager.currentConfig()
        repository.get().saveProxySettings(normalized)
        // Blank password clears the stored secret — do not keep the previous value.
        credentialStore.putProxyPassword(normalized.password)
        val withSecrets = normalized.copy(password = normalized.password)
        proxyManager.updateConfig(withSecrets)
        proxyManager.refreshStatusAndWait()
        if (previous.host != withSecrets.host ||
            previous.port != withSecrets.port ||
            previous.torProvider != withSecrets.torProvider ||
            previous.username != withSecrets.username ||
            previous.password != withSecrets.password ||
            previous.torRequired != withSecrets.torRequired ||
            previous.remoteDns != withSecrets.remoteDns
        ) {
            onProxyStateChanged(proxyManager.isNetworkAllowed(), proxyManager.currentConfig())
        }
    }

    /**
     * True once secondary/primary registration wrote ACI + sessionReady — even if the UI
     * account row is still CONNECTING during link-and-sync archive wait.
     */
    private fun isSignalProvisioned(accountId: String): Boolean {
        val secrets = credentialStore.getAllForAccount(accountId)
        return secrets["sessionReady"] == "true" && !secrets["aci"].isNullOrBlank()
    }

    /** TDLib directory / phone secrets mean a soft-disconnected account is restorable. */
    private fun hasTelegramProvisionedSecrets(accountId: String): Boolean {
        val secrets = credentialStore.getAllForAccount(accountId)
            .filterKeys { !it.startsWith("__") }
        if (secrets.isEmpty()) return false
        // Any non-meta secret (api / phone / db key material) means we should restore, not wipe.
        return secrets.keys.any { it != "displayName" }
    }

    /** True after at least one fully CONNECTED session (survives later ERROR flaps). */
    private fun isAccountEstablished(accountId: String): Boolean =
        credentialStore.get(accountId, ACCOUNT_ESTABLISHED_KEY) == "true"

    companion object {
        private const val ACCOUNT_ESTABLISHED_KEY = "__established"
    }
}
