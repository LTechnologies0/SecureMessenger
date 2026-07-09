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
import ltechnologies.onionphone.securemessenger.core.model.ProxyConfig
import ltechnologies.onionphone.securemessenger.core.proxy.ProxyManager
import ltechnologies.onionphone.securemessenger.core.proxy.SocksEndpointResolver
import okhttp3.OkHttpClient

/**
 * Fail-closed gate for every network path in the app. No socket, HTTP client, or WebView
 * may be created without first passing [assertNetworkAllowed] — this is the single choke
 * point that enforces the "Tor-only, no clearnet fallback" guarantee described in
 * `docs/ARCHITECTURE.md`.
 */
@Singleton
class NetworkGuard @Inject constructor(
    private val proxyManager: ProxyManager,
) {
    /** Throws [NetworkBlockedException] if the Tor SOCKS proxy is not currently reachable. */
    fun assertNetworkAllowed() {
        if (!proxyManager.isNetworkAllowed()) {
            throw NetworkBlockedException(
                proxyManager.status.value.lastError
                    ?: "Réseau bloqué : Tor requis mais proxy SOCKS indisponible",
            )
        }
    }

    /** Returns a plain [Socket], but only after the killswitch check passes. */
    fun createFailClosedSocket(): Socket {
        assertNetworkAllowed()
        return Socket()
    }
}

/** Thrown by [NetworkGuard] when the Tor SOCKS proxy is unavailable and the killswitch is engaged. */
class NetworkBlockedException(message: String) : IllegalStateException(message)

/**
 * Builds Ktor/OkHttp clients that are always configured to route through the current
 * Tor SOCKS5 proxy, gated by [NetworkGuard]. Used by protocol adapters (e.g. Matrix)
 * instead of constructing HTTP clients directly.
 */
@Singleton
class ProxiedHttpClientFactory @Inject constructor(
    private val proxyManager: ProxyManager,
    private val networkGuard: NetworkGuard,
) {
    /** Builds a Ktor [HttpClient] on the OkHttp engine, pre-configured with the Tor SOCKS proxy. */
    fun create(
        configure: HttpClientConfig<OkHttpConfig>.() -> Unit = {},
    ): HttpClient {
        networkGuard.assertNetworkAllowed()
        val config = proxyManager.currentConfig()
        val socksHost = SocksEndpointResolver.resolveReachableHost(config.host, config.port)
        val proxy = Proxy(
            Proxy.Type.SOCKS,
            InetSocketAddress(socksHost, config.port),
        )
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
                preconfigured = OkHttpClient.Builder()
                    .proxy(proxy)
                    .build()
            }
            configure()
        }
    }

    /** Builds a raw [OkHttpClient] pre-configured with the Tor SOCKS proxy. */
    fun okhttpClient(): OkHttpClient {
        networkGuard.assertNetworkAllowed()
        val config = proxyManager.currentConfig()
        val socksHost = SocksEndpointResolver.resolveReachableHost(config.host, config.port)
        return OkHttpClient.Builder()
            .proxy(
                Proxy(
                    Proxy.Type.SOCKS,
                    InetSocketAddress(socksHost, config.port),
                ),
            )
            .build()
    }
}
