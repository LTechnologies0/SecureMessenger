package ltechnologies.onionphone.securemessenger.core.proxy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.InetSocketAddress
import java.net.Socket
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
import kotlinx.coroutines.withTimeout
import ltechnologies.onionphone.securemessenger.core.model.ProxyConfig
import ltechnologies.onionphone.securemessenger.core.model.TorProvider
import timber.log.Timber

@Singleton
class ProxyManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val invizibleHelper: InvizibleHelper,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshMutex = Mutex()
    private var refreshJob: Job? = null
    private var lastOrbotStatus: OrbotHelper.OrbotStatus? = null

    private val _status = MutableStateFlow(
        ProxyStatus(
            config = ProxyConfigNormalizer.normalize(
                ProxyConfig(
                    host = OrbotConstants.SOCKS_HOST,
                    port = OrbotConstants.SOCKS_PORT,
                    torRequired = true,
                    torProvider = TorProvider.CUSTOM,
                ),
            ),
        ),
    )
    val status: StateFlow<ProxyStatus> = _status.asStateFlow()

    private var statusReceiver: BroadcastReceiver? = null

    init {
        registerOrbotReceiver()
        _status.update { it.copy(orbotInstalled = isOrbotInstalled()) }
        if (isOrbotInstalled()) {
            OrbotHelper.requestStatusBroadcast(context)
        }
        refreshStatus()
    }

    fun updateConfig(config: ProxyConfig) {
        val resolved = resolveConfigForProvider(ProxyConfigNormalizer.normalize(config))
        _status.update { it.copy(config = resolved, torProvider = resolved.torProvider) }
        refreshStatus()
    }

    fun setTorProvider(provider: TorProvider) {
        updateConfig(_status.value.config.copy(torProvider = provider))
    }

    fun currentConfig(): ProxyConfig = _status.value.config

    fun isNetworkAllowed(): Boolean =
        evaluateNetworkAllowed(_status.value.config.torRequired, _status.value.proxyHealthy)

    suspend fun ensureProxyReady(): Boolean {
        refreshStatusAndWait()
        if (!isNetworkAllowed() && isOrbotInstalled()) {
            OrbotHelper.requestStatusBroadcast(context)
            delay(600)
            refreshStatusAndWait()
        }
        return isNetworkAllowed()
    }

    suspend fun refreshStatusAndWait() {
        refreshMutex.withLock {
            val done = CompletableDeferred<Unit>()
            refreshJob?.cancel()
            refreshJob = scope.launch {
                try {
                    runHealthCheck(requestOrbotStatus = true)
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
                refreshJob = launch { runHealthCheck(requestOrbotStatus = false) }
            }
        }
    }

    suspend fun requestTorStart(): Boolean = withContext(Dispatchers.IO) {
        when (_status.value.config.torProvider) {
            TorProvider.ORBOT -> requestOrbotStart()
            TorProvider.INVIZIBLE -> requestInvizibleTorUi()
            TorProvider.CUSTOM -> {
                refreshStatusAndWait()
                _status.value.proxyHealthy
            }
        }
    }

    suspend fun requestOrbotStart(): Boolean = withContext(Dispatchers.IO) {
        if (!isOrbotInstalled()) return@withContext false
        OrbotHelper.requestStatusBroadcast(context)
        delay(500)
        refreshStatusAndWait()
        _status.value.proxyHealthy
    }

    suspend fun requestInvizibleTorUi(): Boolean = withContext(Dispatchers.IO) {
        if (!invizibleHelper.isInstalled()) return@withContext false
        invizibleHelper.requestTorUi()
    }

    private suspend fun runHealthCheck(requestOrbotStatus: Boolean) {
        val config = _status.value.config
        val orbotInstalled = isOrbotInstalled()
        val invizibleInstalled = invizibleHelper.isInstalled()

        if (requestOrbotStatus && orbotInstalled && config.torProvider == TorProvider.ORBOT) {
            OrbotHelper.requestStatusBroadcast(context)
            delay(350)
        }

        val resolved = resolveConfigForProvider(config)
        val endpointHost = SocksEndpointResolver.resolveReachableHost(resolved.host, resolved.port)
        val endpoint = resolved.copy(host = endpointHost)
        val check = performSocksCheck(endpoint)
        val healthy = check is SocksCheckResult.Success
        val latency = (check as? SocksCheckResult.Success)?.latencyMs
        val orbot = lastOrbotStatus

        _status.update {
            it.copy(
                config = resolved.copy(host = endpointHost),
                torProvider = resolved.torProvider,
                orbotInstalled = orbotInstalled,
                orbotTorOn = orbot?.torRunning == true,
                orbotStatus = orbot?.status,
                orbotRunning = healthy && orbotInstalled && resolved.torProvider == TorProvider.ORBOT,
                invizibleInstalled = invizibleInstalled,
                invizibleRunning = healthy && invizibleInstalled && resolved.torProvider == TorProvider.INVIZIBLE,
                proxyHealthy = healthy,
                lastCheckLatencyMs = latency,
                lastError = when {
                    resolved.torRequired && !healthy -> buildErrorMessage(resolved, check, orbot)
                    else -> null
                },
            )
        }
    }

    private suspend fun performSocksCheck(config: ProxyConfig): SocksCheckResult =
        when (config.torProvider) {
            TorProvider.INVIZIBLE -> invizibleHelper.checkTorSocksHealthy(
                host = config.host,
                port = config.port,
                username = config.username,
                password = config.password,
                remoteDns = config.remoteDns,
            )
            else -> SocksConnectivityChecker.checkSocksWithRemoteDns(
                proxyHost = config.host,
                proxyPort = config.port,
                username = config.username,
                password = config.password,
                remoteDns = config.remoteDns,
            )
        }

    private fun buildErrorMessage(
        config: ProxyConfig,
        check: SocksCheckResult,
        orbot: OrbotHelper.OrbotStatus?,
    ): String {
        val base = (check as? SocksCheckResult.Failure)?.reason
            ?: "SOCKS proxy unreachable at ${config.host}:${config.port}"
        return when {
            config.torProvider == TorProvider.ORBOT && orbot != null && !orbot.torRunning ->
                "Orbot n'est pas démarré (statut ${orbot.status}). Ouvrez Orbot et activez Tor."
            config.torProvider == TorProvider.ORBOT && orbot != null ->
                "SOCKS Orbot ${orbot.socksHost}:${orbot.socksPort} injoignable — $base"
            config.torProvider == TorProvider.CUSTOM ->
                "SOCKS CUSTOM ${config.host}:${config.port} — $base"
            else -> base
        }
    }

    suspend fun checkSocksHealth(host: String, port: Int): Boolean =
        SocksConnectivityChecker.checkTcpOnly(host, port)

    fun isOrbotInstalled(): Boolean = OrbotHelper.isInstalled(context)

    private fun resolveConfigForProvider(config: ProxyConfig): ProxyConfig = when (config.torProvider) {
        TorProvider.ORBOT -> {
            val orbot = lastOrbotStatus
            ProxyConfigNormalizer.normalize(
                config.copy(
                    host = orbot?.socksHost ?: config.host,
                    port = orbot?.socksPort ?: config.port,
                ),
            )
        }
        TorProvider.INVIZIBLE -> ProxyConfigNormalizer.normalize(
            config.copy(
                host = InvizibleConstants.LOOPBACK,
                port = invizibleHelper.resolveSocksPort(),
            ),
        )
        TorProvider.CUSTOM -> ProxyConfigNormalizer.normalize(config)
    }

    private fun registerOrbotReceiver() {
        if (statusReceiver != null) return
        statusReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != OrbotConstants.ACTION_STATUS) return
                val parsed = OrbotHelper.parseStatusIntent(intent) ?: return
                lastOrbotStatus = parsed
                Timber.i("Orbot status=${parsed.status} SOCKS ${parsed.socksHost}:${parsed.socksPort}")
                _status.update { state ->
                    val updatedConfig = if (state.config.torProvider == TorProvider.ORBOT) {
                        ProxyConfigNormalizer.normalize(
                            state.config.copy(host = parsed.socksHost, port = parsed.socksPort),
                        )
                    } else {
                        state.config
                    }
                    state.copy(
                        config = updatedConfig,
                        orbotTorOn = parsed.torRunning,
                        orbotStatus = parsed.status,
                    )
                }
                scope.launch {
                    refreshMutex.withLock {
                        runHealthCheck(requestOrbotStatus = false)
                    }
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            statusReceiver,
            IntentFilter(OrbotConstants.ACTION_STATUS),
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    fun unregisterReceiver() {
        statusReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (_: IllegalArgumentException) {
            }
            statusReceiver = null
        }
    }
}
