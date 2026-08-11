package ltechnologies.onionphone.securemessenger.protocol.email

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import javax.net.SocketFactory
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.SNIHostName
import ltechnologies.onionphone.securemessenger.core.model.ProxyConfig
import ltechnologies.onionphone.securemessenger.core.network.ProxiedHttpClientFactory
import ltechnologies.onionphone.securemessenger.core.proxy.SocksEndpointResolver
import okhttp3.Request
import timber.log.Timber

/**
 * Angus Mail builds `InetSocketAddress(hostname, port)` which resolves on-device before SOCKS.
 * Under Tor + [ProxyConfig.remoteDns] we resolve A records via DoH through SOCKS, connect by IP,
 * and set SNI / hostname verification to the original hostname.
 */
object EmailTorDns {
    fun resolveIpv4(hostname: String, httpFactory: ProxiedHttpClientFactory): String? {
        if (hostname.isBlank()) return null
        if (looksLikeIpv4(hostname)) return hostname
        return try {
            val client = httpFactory.okhttpClient()
            val url =
                "https://cloudflare-dns.com/dns-query?name=" +
                    java.net.URLEncoder.encode(hostname, Charsets.UTF_8.name()) +
                    "&type=A"
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/dns-json")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body.string()
                // Prefer type A answers: "type":1 ... "data":"1.2.3.4"
                val dataRegex = Regex("\"data\"\\s*:\\s*\"([0-9.]+)\"")
                dataRegex.findAll(body).map { it.groupValues[1] }
                    .firstOrNull { looksLikeIpv4(it) }
            }
        } catch (e: Exception) {
            Timber.w(e, "Tor DoH resolve failed for %s", hostname)
            null
        }
    }

    fun looksLikeIpv4(value: String): Boolean =
        value.matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$"""))

    fun plainSocksFactory(proxy: ProxyConfig): SocketFactory {
        val socksHost = SocksEndpointResolver.resolveReachableHost(proxy.host, proxy.port)
        return object : SocketFactory() {
            override fun createSocket(): Socket =
                Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress(socksHost, proxy.port)))

            override fun createSocket(host: String, port: Int): Socket {
                val s = createSocket()
                s.connect(InetSocketAddress.createUnresolved(host, port), 30_000)
                return s
            }

            override fun createSocket(
                host: String,
                port: Int,
                localHost: java.net.InetAddress,
                localPort: Int,
            ): Socket = createSocket(host, port)

            override fun createSocket(host: java.net.InetAddress, port: Int): Socket =
                createSocket(host.hostAddress ?: host.hostName, port)

            override fun createSocket(
                address: java.net.InetAddress,
                port: Int,
                localAddress: java.net.InetAddress,
                localPort: Int,
            ): Socket = createSocket(address, port)
        }
    }

    fun sslFactory(serverName: String): SSLSocketFactory {
        val delegate = SSLSocketFactory.getDefault() as SSLSocketFactory
        return object : SSLSocketFactory() {
            override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
            override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

            private fun tune(ssl: SSLSocket): SSLSocket {
                runCatching {
                    val params = ssl.sslParameters
                    params.serverNames = listOf(SNIHostName(serverName))
                    params.endpointIdentificationAlgorithm = "HTTPS"
                    ssl.sslParameters = params
                }
                return ssl
            }

            override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket =
                tune(delegate.createSocket(s, serverName, port, autoClose) as SSLSocket)

            override fun createSocket(host: String, port: Int): Socket =
                tune(delegate.createSocket(host, port) as SSLSocket)

            override fun createSocket(
                host: String,
                port: Int,
                localHost: java.net.InetAddress,
                localPort: Int,
            ): Socket = tune(delegate.createSocket(host, port, localHost, localPort) as SSLSocket)

            override fun createSocket(host: java.net.InetAddress, port: Int): Socket =
                tune(delegate.createSocket(host, port) as SSLSocket)

            override fun createSocket(
                address: java.net.InetAddress,
                port: Int,
                localAddress: java.net.InetAddress,
                localPort: Int,
            ): Socket = tune(delegate.createSocket(address, port, localAddress, localPort) as SSLSocket)
        }
    }

    /** Verifies the peer cert against [expectedHost], not the IP used for TCP. */
    fun hostnameVerifier(expectedHost: String): HostnameVerifier =
        HostnameVerifier { _, session: SSLSession ->
            javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier()
                .verify(expectedHost, session)
        }
}
