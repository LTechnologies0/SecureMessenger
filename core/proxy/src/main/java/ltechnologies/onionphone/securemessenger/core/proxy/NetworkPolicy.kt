package ltechnologies.onionphone.securemessenger.core.proxy

/**
 * Fail-closed: outbound traffic is allowed only when the Tor/SOCKS proxy is healthy.
 */
fun evaluateNetworkAllowed(torRequired: Boolean, proxyHealthy: Boolean): Boolean =
    proxyHealthy
