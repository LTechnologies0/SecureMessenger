package ltechnologies.onionphone.securemessenger.core.proxy

/**
 * Constants derived from [Gedsh/InviZible](https://github.com/Gedsh/InviZible) `tordnscrypt` sources.
 *
 * External apps cannot start [ModulesService] (exported=false); integration is SOCKS health-check
 * plus launching InviZible so the user can start Tor manually.
 */
object InvizibleConstants {
    /** F-Droid / stable release applicationId. */
    const val PACKAGE_STABLE = "pan.alexander.tordnscrypt.stable"

    /** Google Play variant. */
    const val PACKAGE_GP = "pan.alexander.tordnscrypt.gp"

    val KNOWN_PACKAGES = listOf(PACKAGE_STABLE, PACKAGE_GP)

    const val LOOPBACK = "127.0.0.1"

    /** Default Tor SOCKS port from `preferences_tor.xml` (`SOCKSPort`). */
    const val DEFAULT_TOR_SOCKS_PORT = 9050

    /** Outbound proxy default from `Constants.DEFAULT_PROXY_PORT` (chaining, not Tor SOCKS). */
    const val DEFAULT_OUTBOUND_PROXY_PORT = 1080

    /** SharedPreferences key for Tor SOCKS port (`PreferenceKeys.TOR_SOCKS_PORT`). */
    const val PREF_SOCKS_PORT = "SOCKSPort"

    /** SharedPreferences file used by InviZible (`DEFAULT_PREFERENCES_NAME`). */
    const val PREF_FILE = "pan.alexander.tordnscrypt_preferences"

    const val MAIN_ACTIVITY = "pan.alexander.tordnscrypt.MainActivity"

    /** DNS endpoint used by InviZible `ProxyHelper.checkProxyConnectivity`. */
    const val DNS_PROBE_HOST = "94.140.14.41"

    const val DNS_PROBE_PORT = 53

    const val CONNECT_TIMEOUT_MS = 5_000
}
