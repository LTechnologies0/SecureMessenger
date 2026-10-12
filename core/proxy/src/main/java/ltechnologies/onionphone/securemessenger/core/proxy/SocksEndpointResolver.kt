package ltechnologies.onionphone.securemessenger.core.proxy

/**
 * Resolves the SOCKS endpoint reachable from this Android runtime.
 *
 * Loopback stays loopback. Gateway / emulator alias `10.0.2.2` / Waydroid
 * `192.168.240.1` are never auto-selected — those IPs skip in-guest OnionVPN
 * SOCKS and can leak DNS. Custom (non-loopback) SOCKS hosts are returned unchanged.
 */
object SocksEndpointResolver {

    /**
     * Returns a SOCKS host. Loopback stays loopback. Any other configured host is kept as-is.
     * [port] is unused: there is no TCP candidate race in release.
     */
    @Suppress("UNUSED_PARAMETER")
    fun resolveReachableHost(configuredHost: String, port: Int = OnionVpnConstants.DEFAULT_BRIDGE_PORT): String =
        ProxyConfigNormalizer.normalizeHost(configuredHost)

    /** Host-side Tor bridges that are loopback only. */
    fun isHostBridgeEndpoint(host: String): Boolean {
        val normalized = ProxyConfigNormalizer.normalizeHost(host)
        return normalized == OnionVpnConstants.LOOPBACK
    }
}
