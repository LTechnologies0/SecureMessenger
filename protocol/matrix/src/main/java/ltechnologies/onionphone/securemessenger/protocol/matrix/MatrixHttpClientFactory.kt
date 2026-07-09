package ltechnologies.onionphone.securemessenger.protocol.matrix

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import java.net.InetSocketAddress
import java.net.Proxy
import kotlinx.serialization.json.Json
import ltechnologies.onionphone.securemessenger.core.model.ProxyConfig
import ltechnologies.onionphone.securemessenger.core.proxy.SocksEndpointResolver
import okhttp3.OkHttpClient

/** Builds a SOCKS-proxied Ktor HTTP client for raw Matrix CS API calls (login, register, ...). */
internal object MatrixHttpClientFactory {
    fun create(proxy: ProxyConfig): HttpClient {
        val socksHost = SocksEndpointResolver.resolveReachableHost(proxy.host, proxy.port)
        val okhttp = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(socksHost, proxy.port)))
            .build()
        return HttpClient(OkHttp) {
            engine { preconfigured = okhttp }
            install(ContentNegotiation) {
                // encodeDefaults=true is REQUIRED so fields like Identifier.type ("m.id.user")
                // are serialized; Matrix rejects requests whose identifier omits `type`.
                json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
            }
        }
    }
}
