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
            torRequired = true,
            remoteDns = true,
        )
    }

    fun normalizeHost(host: String): String = when (host.trim().lowercase()) {
        "", "localhost", "::1" -> OrbotConstants.SOCKS_HOST
        else -> host.trim()
    }

    fun configForSave(
        torProvider: TorProvider,
        customHost: String,
        customPort: Int,
        resolvedStatus: ProxyConfig,
        username: String? = null,
        password: String? = null,
    ): ProxyConfig = when (torProvider) {
        TorProvider.ORBOT -> normalize(
            resolvedStatus.copy(
                torProvider = TorProvider.ORBOT,
                username = null,
                password = null,
            ),
        )
        TorProvider.INVIZIBLE -> normalize(
            resolvedStatus.copy(
                torProvider = TorProvider.INVIZIBLE,
                username = null,
                password = null,
            ),
        )
        TorProvider.CUSTOM -> normalize(
            ProxyConfig(
                host = customHost,
                port = customPort,
                username = username?.takeIf { it.isNotBlank() },
                password = password?.takeIf { it.isNotBlank() },
                torRequired = true,
                remoteDns = true,
                torProvider = TorProvider.CUSTOM,
            ),
        )
    }
}
