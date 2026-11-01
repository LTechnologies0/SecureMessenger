package ltechnologies.onionphone.securemessenger.core.network

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import ltechnologies.onionphone.securemessenger.core.model.ProxyConfig
import ltechnologies.onionphone.securemessenger.core.proxy.SocksEndpointResolver
import okhttp3.Dns
import okhttp3.OkHttpClient

/**
 * OkHttp wired for Tor SOCKS5 **with TLS inside the tunnel**.
 *
 * System DNS is forbidden: [socksRemoteDns] returns a placeholder [InetAddress] that keeps the
 * hostname (for SNI) while OkHttp's SOCKS connector sends the name to Tor (SOCKS5 DOMAIN).
 * Without this, OkHttp resolves on-device first — under Tor-only that fails or leaks.
 *
 * Note: OkHttp [okhttp3.Authenticator] / `proxyAuthenticator` is HTTP-proxy only; it does **not**
 * implement SOCKS5 USERNAME/PASSWORD. The OnionVPN PAC bridge authenticates to Tor itself, so
 * client credentials are optional here.
 */
object TorSocksOkHttp {
    /**
     * Placeholder A records so OkHttp never calls the system resolver.
     * Hostname is preserved on the InetAddress for TLS SNI.
     */
    val socksRemoteDns: Dns = Dns { hostname ->
        if (hostname.matches(IPV4)) {
            listOf(InetAddress.getByAddress(hostname, parseIpv4(hostname)))
        } else {
            listOf(InetAddress.getByAddress(hostname, byteArrayOf(0, 0, 0, 1)))
        }
    }

    fun builder(proxy: ProxyConfig): OkHttpClient.Builder {
        val socksHost = SocksEndpointResolver.resolveReachableHost(proxy.host, proxy.port)
        return OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(socksHost, proxy.port)))
            .dns(socksRemoteDns)
            .connectTimeout(45, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
    }

    fun client(proxy: ProxyConfig): OkHttpClient = builder(proxy).build()

    private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    private fun parseIpv4(value: String): ByteArray {
        val parts = value.split('.')
        return ByteArray(4) { i -> parts[i].toInt().coerceIn(0, 255).toByte() }
    }
}
