package ltechnologies.onionphone.securemessenger.core.proxy

/**
 * Network gate used by [ltechnologies.onionphone.securemessenger.core.proxy.ProxyManager].
 *
 * Tor routing is optional. When [torRequired] is false, clearnet is always allowed.
 * When true, traffic that opts into Tor still needs a healthy SOCKS endpoint — but there
 * is no global killswitch that tears down other protocols.
 */
fun evaluateNetworkAllowed(torRequired: Boolean, proxyHealthy: Boolean): Boolean =
    !torRequired || proxyHealthy
