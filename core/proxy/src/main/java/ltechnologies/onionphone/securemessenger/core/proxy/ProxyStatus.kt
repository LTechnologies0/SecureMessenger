package ltechnologies.onionphone.securemessenger.core.proxy

import ltechnologies.onionphone.securemessenger.core.model.ProxyConfig
import ltechnologies.onionphone.securemessenger.core.model.TorProvider

data class ProxyStatus(
    val config: ProxyConfig,
    val torProvider: TorProvider = config.torProvider,
    val onionVpnInstalled: Boolean = false,
    val onionVpnRunning: Boolean = false,
    val pacUrl: String = OnionVpnConstants.PAC_URL,
    val proxyHealthy: Boolean = false,
    val lastCheckLatencyMs: Long? = null,
    val lastError: String? = null,
)
