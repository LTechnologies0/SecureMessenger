package ltechnologies.onionphone.securemessenger.core.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.engine.okhttp.OkHttpConfig
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import ltechnologies.onionphone.securemessenger.core.proxy.ProxyManager
import okhttp3.OkHttpClient

/**
 * Mandatory Tor gate. Clearnet is never allowed — SOCKS must be healthy.
 */
@Singleton
class NetworkGuard @Inject constructor(
    private val proxyManager: ProxyManager,
) {
    /** Throws [NetworkBlockedException] when OnionVPN/custom SOCKS is down. */
    fun assertNetworkAllowed() {
        if (!proxyManager.isNetworkAllowed()) {
            throw NetworkBlockedException(
                proxyManager.status.value.lastError
                    ?: "Tor requis : proxy SOCKS OnionVPN indisponible — démarrez OnionVPN",
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
 * Builds Ktor/OkHttp clients that always use Tor SOCKS. No clearnet builder path.
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
        return TorSocksOkHttp.client(proxyManager.currentConfig())
    }
}
