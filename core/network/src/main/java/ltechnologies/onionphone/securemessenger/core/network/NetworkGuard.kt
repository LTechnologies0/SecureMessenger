package ltechnologies.onionphone.securemessenger.core.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.engine.okhttp.OkHttpConfig
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import ltechnologies.onionphone.securemessenger.core.proxy.ProxyManager
import ltechnologies.onionphone.securemessenger.core.proxy.SocksEndpointResolver
import okhttp3.OkHttpClient

/**
 * Optional Tor gate. Clearnet is allowed when Tor is not required.
 * Protocols that opt into Tor still route through SOCKS when the proxy is healthy.
 */
@Singleton
class NetworkGuard @Inject constructor(
    private val proxyManager: ProxyManager,
) {
    /** Throws [NetworkBlockedException] only when Tor is required and SOCKS is down. */
    fun assertNetworkAllowed() {
        if (!proxyManager.isNetworkAllowed()) {
            throw NetworkBlockedException(
                proxyManager.status.value.lastError
                    ?: "Tor activé mais proxy SOCKS indisponible — désactivez Tor ou démarrez Orbot/InviZible",
            )
        }
    }

    fun createFailClosedSocket(): Socket {
        assertNetworkAllowed()
        return Socket()
    }
}

/** Thrown by [NetworkGuard] when Tor is required and the SOCKS proxy is unavailable. */
class NetworkBlockedException(message: String) : IllegalStateException(message)

/**
 * Builds Ktor/OkHttp clients. Uses Tor SOCKS only when Tor routing is enabled and healthy;
 * otherwise connects directly (clearnet).
 */
@Singleton
class ProxiedHttpClientFactory @Inject constructor(
    private val proxyManager: ProxyManager,
    private val networkGuard: NetworkGuard,
) {
    fun create(
        configure: HttpClientConfig<OkHttpConfig>.() -> Unit = {},
    ): HttpClient {
        networkGuard.assertNetworkAllowed()
        return HttpClient(OkHttp) {
            install(WebSockets)
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                    },
                )
            }
            engine {
                preconfigured = okhttpClient()
            }
            configure()
        }
    }

    fun okhttpClient(): OkHttpClient {
        networkGuard.assertNetworkAllowed()
        val config = proxyManager.currentConfig()
        val builder = OkHttpClient.Builder()
        if (config.torRequired && proxyManager.status.value.proxyHealthy) {
            val socksHost = SocksEndpointResolver.resolveReachableHost(config.host, config.port)
            builder.proxy(
                Proxy(
                    Proxy.Type.SOCKS,
                    InetSocketAddress(socksHost, config.port),
                ),
            )
        }
        return builder.build()
    }
}
