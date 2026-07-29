package ltechnologies.onionphone.securemessenger.core.proxy

/**
 * OnionVPN PAC / SOCKS bridge constants.
 *
 * Stable PAC URL: [PAC_URL]. The PAC body points at the DNSCrypt→Tor SOCKS bridge
 * ([DEFAULT_BRIDGE_HOST]:[DEFAULT_BRIDGE_PORT]), not raw Tor SocksPort.
 *
 * @see <a href="https://github.com/LTechnologies0/OnionVPN">OnionVPN</a>
 */
object OnionVpnConstants {
    const val PACKAGE_NAME = "ltechnologies.onionphone.onionvpn"

    const val LOOPBACK = "127.0.0.1"

    /** Stable PAC HTTP listen port (does not change across OnionVPN sessions). */
    const val PAC_LISTEN_PORT = 18_201

    const val PAC_PATH = "/onionvpn.pac"

    /** Fixed SOCKS5 bridge: DNSCrypt resolve → Tor CONNECT by IP. */
    const val DEFAULT_BRIDGE_HOST = LOOPBACK
    const val DEFAULT_BRIDGE_PORT = 18_202

    /** Alias used by PAC client fallback when the script is missing a SOCKS line. */
    const val FALLBACK_SOCKS_PORT = DEFAULT_BRIDGE_PORT

    const val PAC_URL = "http://$LOOPBACK:$PAC_LISTEN_PORT$PAC_PATH"

    const val HEALTH_URL = "http://$LOOPBACK:$PAC_LISTEN_PORT/health"

    const val MAIN_ACTIVITY = "ltechnologies.onionphone.onionvpn.MainActivity"

    const val CONNECT_TIMEOUT_MS = 3_000

    /** Regex for `SOCKS5 host:port` lines in the PAC script. */
    val SOCKS5_IN_PAC = Regex("""SOCKS5\s+([^\s;:]+):(\d+)""", RegexOption.IGNORE_CASE)
}
