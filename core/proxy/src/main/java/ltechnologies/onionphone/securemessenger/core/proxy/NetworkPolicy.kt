package ltechnologies.onionphone.securemessenger.core.proxy

/**
 * Network gate used by [ProxyManager] and [ltechnologies.onionphone.securemessenger.core.network.NetworkGuard].
 *
 * Tor is mandatory. Clearnet is never allowed — [proxyHealthy] must be true.
 * [torRequired] is kept for stored configs; it is not an escape hatch to skip SOCKS.
 */
fun evaluateNetworkAllowed(@Suppress("UNUSED_PARAMETER") torRequired: Boolean, proxyHealthy: Boolean): Boolean =
    proxyHealthy
