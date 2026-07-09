package ltechnologies.onionphone.securemessenger.core.proxy

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * SOCKS reachability checks.
 * Remote-DNS probes use [InetSocketAddress.createUnresolved] so the hostname is resolved by Tor,
 * not on-device DNS.
 */
object SocksConnectivityChecker {

    suspend fun checkTcpOnly(host: String, port: Int, timeoutMs: Long = 5_000): Boolean =
        withContext(Dispatchers.IO) {
            try {
                withTimeout(timeoutMs) {
                    Socket().use { socket ->
                        socket.connect(
                            InetSocketAddress(ProxyConfigNormalizer.normalizeHost(host), port),
                            (timeoutMs - 500).toInt().coerceAtLeast(1_000),
                        )
                    }
                }
                true
            } catch (_: Exception) {
                false
            }
        }

    suspend fun checkSocksWithRemoteDns(
        proxyHost: String,
        proxyPort: Int,
        username: String? = null,
        password: String? = null,
        dnsHost: String = InvizibleConstants.DNS_PROBE_HOST,
        dnsPort: Int = InvizibleConstants.DNS_PROBE_PORT,
        timeoutMs: Int = InvizibleConstants.CONNECT_TIMEOUT_MS,
        remoteDns: Boolean = true,
    ): SocksCheckResult = withContext(Dispatchers.IO) {
        val normalizedHost = ProxyConfigNormalizer.normalizeHost(proxyHost)
        if (!checkTcpOnly(normalizedHost, proxyPort, timeoutMs.toLong())) {
            return@withContext SocksCheckResult.Failure(
                "Proxy SOCKS injoignable sur $normalizedHost:$proxyPort (vérifiez qu'Orbot/Tor est démarré)",
            )
        }
        if (!remoteDns) {
            return@withContext SocksCheckResult.Success(0)
        }

        val started = System.currentTimeMillis()
        try {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(normalizedHost, proxyPort))
            val target = InetSocketAddress.createUnresolved(dnsHost, dnsPort)
            Socket(proxy).use { socket ->
                socket.connect(target, timeoutMs)
                socket.soTimeout = timeoutMs
                if (!socket.isConnected) {
                    return@withContext SocksCheckResult.Failure(
                        "Connexion SOCKS vers $dnsHost impossible (DNS distant via Tor)",
                    )
                }
            }
            SocksCheckResult.Success(System.currentTimeMillis() - started)
        } catch (e: Exception) {
            // On host-side SOCKS bridges (loopback, emulator alias, Waydroid gateway), TCP
            // reachability is enough — remote-DNS probes often time out even when Tor works.
            if (SocksEndpointResolver.isHostBridgeEndpoint(proxyHost)) {
                return@withContext SocksCheckResult.Success(0)
            }
            SocksCheckResult.Failure(
                e.message ?: "Échec du test SOCKS+DNS distant via $normalizedHost:$proxyPort",
            )
        }
    }
}

sealed class SocksCheckResult {
    data class Success(val latencyMs: Long) : SocksCheckResult()
    data class Failure(val reason: String) : SocksCheckResult()
}
