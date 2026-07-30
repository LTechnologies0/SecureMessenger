package ltechnologies.onionphone.securemessenger.core.proxy

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ltechnologies.onionphone.securemessenger.core.model.ProxyConfig
import ltechnologies.onionphone.securemessenger.core.model.TorProvider
import timber.log.Timber

@Singleton
class ProxyManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val onionVpnHelper: OnionVpnHelper,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshMutex = Mutex()
    private var refreshJob: Job? = null

    private val _status = MutableStateFlow(
        ProxyStatus(
            config = ProxyConfigNormalizer.normalize(
                ProxyConfig(
                    host = OnionVpnConstants.DEFAULT_BRIDGE_HOST,
                    port = OnionVpnConstants.DEFAULT_BRIDGE_PORT,
                    torRequired = false,
                    torProvider = TorProvider.ONIONVPN,
                ),
            ),
            pacUrl = OnionVpnConstants.PAC_URL,
            onionVpnInstalled = false,
        ),
    )
    val status: StateFlow<ProxyStatus> = _status.asStateFlow()

    init {
        _status.update { it.copy(onionVpnInstalled = onionVpnHelper.isInstalled()) }
        refreshStatus()
    }

    fun updateConfig(config: ProxyConfig) {
        scope.launch {
            refreshMutex.withLock {
                val resolved = resolveConfigForProvider(ProxyConfigNormalizer.normalize(config))
                _status.update { it.copy(config = resolved, torProvider = resolved.torProvider) }
                runHealthCheck()
            }
        }
    }

    fun setTorProvider(provider: TorProvider) {
        updateConfig(_status.value.config.copy(torProvider = provider))
    }

    fun currentConfig(): ProxyConfig = _status.value.config

    fun isNetworkAllowed(): Boolean =
        evaluateNetworkAllowed(_status.value.config.torRequired, _status.value.proxyHealthy)

    suspend fun ensureProxyReady(): Boolean {
        refreshStatusAndWait()
        return isNetworkAllowed()
    }

    suspend fun refreshStatusAndWait() {
        refreshMutex.withLock {
            val done = CompletableDeferred<Unit>()
            refreshJob?.cancel()
            refreshJob = scope.launch {
                try {
                    runHealthCheck()
                } finally {
                    done.complete(Unit)
                }
            }
            done.await()
        }
    }

    fun refreshStatus() {
        scope.launch {
            refreshMutex.withLock {
                refreshJob?.cancel()
                refreshJob = launch { runHealthCheck() }
            }
        }
    }

    /** Opens OnionVPN (or releases page) when using ONIONVPN; otherwise rechecks SOCKS. */
    suspend fun requestTorStart(): Boolean = withContext(Dispatchers.IO) {
        when (_status.value.config.torProvider) {
            TorProvider.ONIONVPN -> {
                onionVpnHelper.openAppOrReleases()
                delay(800)
                refreshStatusAndWait()
                _status.value.proxyHealthy
            }
            TorProvider.CUSTOM -> {
                refreshStatusAndWait()
                _status.value.proxyHealthy
            }
        }
    }

    private suspend fun runHealthCheck() {
        val config = _status.value.config
        val onionVpnInstalled = onionVpnHelper.isInstalled()
        val resolved = resolveConfigForProvider(config)
        val endpointHost = SocksEndpointResolver.resolveReachableHost(resolved.host, resolved.port)
        val endpoint = resolved.copy(host = endpointHost)
        val check = SocksConnectivityChecker.checkSocksWithRemoteDns(
            proxyHost = endpoint.host,
            proxyPort = endpoint.port,
            username = endpoint.username,
            password = endpoint.password,
            remoteDns = endpoint.remoteDns,
        )
        val healthy = check is SocksCheckResult.Success
        val latency = (check as? SocksCheckResult.Success)?.latencyMs

        _status.update {
            it.copy(
                config = resolved.copy(host = endpointHost),
                torProvider = resolved.torProvider,
                onionVpnInstalled = onionVpnInstalled,
                onionVpnRunning = healthy && resolved.torProvider == TorProvider.ONIONVPN,
                pacUrl = OnionVpnConstants.PAC_URL,
                proxyHealthy = healthy,
                lastCheckLatencyMs = latency,
                lastError = when {
                    resolved.torRequired && !healthy -> buildErrorMessage(resolved, check)
                    else -> null
                },
            )
        }
    }

    private fun buildErrorMessage(config: ProxyConfig, check: SocksCheckResult): String {
        val base = (check as? SocksCheckResult.Failure)?.reason
            ?: "SOCKS proxy unreachable at ${config.host}:${config.port}"
        return when (config.torProvider) {
            TorProvider.ONIONVPN ->
                "OnionVPN SOCKS ${config.host}:${config.port} injoignable — " +
                    "démarrez le tunnel OnionVPN ($base). PAC : ${OnionVpnConstants.PAC_URL}"
            TorProvider.CUSTOM ->
                "SOCKS CUSTOM ${config.host}:${config.port} — $base"
        }
    }

    suspend fun checkSocksHealth(host: String, port: Int): Boolean =
        SocksConnectivityChecker.checkTcpOnly(host, port)

    private fun resolveConfigForProvider(config: ProxyConfig): ProxyConfig = when (config.torProvider) {
        TorProvider.ONIONVPN -> {
            val endpoint = OnionVpnPacClient.resolveSocksEndpoint().getOrElse {
                Timber.d(it, "OnionVPN PAC resolve failed; using fallback")
                OnionVpnSocksEndpoint(
                    host = OnionVpnConstants.DEFAULT_BRIDGE_HOST,
                    port = OnionVpnConstants.DEFAULT_BRIDGE_PORT,
                    fromPac = false,
                )
            }
            ProxyConfigNormalizer.normalize(
                config.copy(
                    host = endpoint.host,
                    port = endpoint.port,
                    username = null,
                    password = null,
                ),
            )
        }
        TorProvider.CUSTOM -> ProxyConfigNormalizer.normalize(config)
    }

    /** No-op kept for call sites that previously unregistered Orbot receivers. */
    fun unregisterReceiver() = Unit
}
