package ltechnologies.onionphone.securemessenger.core.proxy

import java.net.HttpURLConnection
import java.net.URL
import timber.log.Timber

data class OnionVpnSocksEndpoint(
    val host: String,
    val port: Int,
    val fromPac: Boolean,
)

/**
 * Discovers OnionVPN SOCKS via the stable PAC URL (preferred) or `/health` + fallback port.
 * Client → PAC bridge uses SOCKS5 **without** credentials (bridge authenticates to Tor itself).
 */
object OnionVpnPacClient {

    private val failClosed = Regex("""(?i)PROXY\s+127\.0\.0\.1:1""")

    fun isBridgeHealthy(timeoutMs: Int = OnionVpnConstants.CONNECT_TIMEOUT_MS): Boolean =
        runCatching {
            val body = httpGet(OnionVpnConstants.HEALTH_URL, timeoutMs) ?: return false
            body.startsWith("ok", ignoreCase = true)
        }.getOrDefault(false)

    /**
     * Fetch PAC and parse the SOCKS5 endpoint. Fail-closed: no fallback to the default
     * bridge if PAC is down, fail-closed (`PROXY 127.0.0.1:1`), or unparsable.
     */
    fun resolveSocksEndpoint(
        timeoutMs: Int = OnionVpnConstants.CONNECT_TIMEOUT_MS,
    ): Result<OnionVpnSocksEndpoint> =
        runCatching {
            val pac = httpGet(OnionVpnConstants.PAC_URL, timeoutMs)
                ?: error("OnionVPN PAC unreachable — démarrez OnionVPN")
            if (failClosed.containsMatchIn(pac) && OnionVpnConstants.SOCKS5_IN_PAC.find(pac) == null) {
                error("OnionVPN tunnel down (PAC fail-closed)")
            }
            parseSocksFromPac(pac)
                ?: error("OnionVPN PAC unparsable — no SOCKS5 endpoint (fail-closed)")
        }

    fun parseSocksFromPac(body: String): OnionVpnSocksEndpoint? {
        val match = OnionVpnConstants.SOCKS5_IN_PAC.find(body) ?: return null
        val host = match.groupValues[1].trim().removePrefix("[").removeSuffix("]")
        val port = match.groupValues[2].toIntOrNull()?.takeIf { it in 1..65_535 } ?: return null
        return OnionVpnSocksEndpoint(
            host = ProxyConfigNormalizer.normalizeHost(host),
            port = port,
            fromPac = true,
        )
    }

    private fun httpGet(url: String, timeoutMs: Int): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "GET"
                instanceFollowRedirects = false
                useCaches = false
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                Timber.d("OnionVPN HTTP %d for %s", code, url)
                return null
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Timber.d(e, "OnionVPN GET failed %s", url)
            null
        } finally {
            conn?.disconnect()
        }
    }
}
