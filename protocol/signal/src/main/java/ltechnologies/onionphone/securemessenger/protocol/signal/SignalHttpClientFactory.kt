package ltechnologies.onionphone.securemessenger.protocol.signal

import java.net.InetSocketAddress
import java.net.Proxy
import ltechnologies.onionphone.securemessenger.core.model.ProxyConfig
import ltechnologies.onionphone.securemessenger.core.proxy.SocksEndpointResolver
import okhttp3.OkHttpClient

/** SOCKS-proxied OkHttp for Signal service REST calls (Tor-only). */
internal object SignalHttpClientFactory {
    fun create(proxy: ProxyConfig): OkHttpClient {
        val socksHost = SocksEndpointResolver.resolveReachableHost(proxy.host, proxy.port)
        val socks = Proxy(Proxy.Type.SOCKS, InetSocketAddress(socksHost, proxy.port))
        return OkHttpClient.Builder()
            .proxy(socks)
            .build()
    }
}
