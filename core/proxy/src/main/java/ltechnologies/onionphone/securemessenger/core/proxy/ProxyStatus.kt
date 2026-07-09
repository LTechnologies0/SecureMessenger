package ltechnologies.onionphone.securemessenger.core.proxy

import ltechnologies.onionphone.securemessenger.core.model.ProxyConfig
import ltechnologies.onionphone.securemessenger.core.model.TorProvider

data class ProxyStatus(
    val config: ProxyConfig,
    val torProvider: TorProvider = config.torProvider,
    val orbotInstalled: Boolean = false,
    val orbotTorOn: Boolean = false,
    val orbotStatus: String? = null,
    val orbotRunning: Boolean = false,
    val invizibleInstalled: Boolean = false,
    val invizibleRunning: Boolean = false,
    val proxyHealthy: Boolean = false,
    val lastCheckLatencyMs: Long? = null,
    val lastError: String? = null,
)
