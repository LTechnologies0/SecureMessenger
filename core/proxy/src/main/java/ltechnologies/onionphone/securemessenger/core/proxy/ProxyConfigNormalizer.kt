package ltechnologies.onionphone.securemessenger.core.proxy

import ltechnologies.onionphone.securemessenger.core.model.ProxyConfig
import ltechnologies.onionphone.securemessenger.core.model.TorProvider

/** Normalizes SOCKS endpoints so Tor clients on loopback behave consistently. */
object ProxyConfigNormalizer {

    fun normalize(config: ProxyConfig): ProxyConfig {
        val host = normalizeHost(config.host)
        val port = config.port.coerceIn(1, 65_535)
        return config.copy(
            host = host,
            port = port,
            remoteDns = if (config.torRequired) true else config.remoteDns,
        )
    }

    fun normalizeHost(host: String): String = when (host.trim().lowercase()) {
        "", "localhost", "::1" -> OnionVpnConstants.LOOPBACK
        else -> host.trim()
    }

    fun configForSave(
        torProvider: TorProvider,
        customHost: String,
        customPort: Int,
        resolvedStatus: ProxyConfig,
        username: String? = null,
        password: String? = null,
        torRequired: Boolean = resolvedStatus.torRequired,
    ): ProxyConfig = when (torProvider) {
        TorProvider.ONIONVPN -> normalize(
            ProxyConfig(
                host = OnionVpnConstants.DEFAULT_BRIDGE_HOST,
                port = OnionVpnConstants.DEFAULT_BRIDGE_PORT,
                username = null,
                password = null,
                torRequired = torRequired,
                remoteDns = torRequired,
                torProvider = TorProvider.ONIONVPN,
            ),
        )
        TorProvider.CUSTOM -> normalize(
            ProxyConfig(
                host = customHost,
                port = customPort,
                username = username?.takeIf { it.isNotBlank() },
                password = password?.takeIf { it.isNotBlank() },
                torRequired = torRequired,
                remoteDns = torRequired,
                torProvider = TorProvider.CUSTOM,
            ),
        )
    }
}
