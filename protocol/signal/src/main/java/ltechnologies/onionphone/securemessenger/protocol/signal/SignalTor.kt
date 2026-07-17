package ltechnologies.onionphone.securemessenger.protocol.signal

import java.net.InetSocketAddress
import java.net.Proxy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import ltechnologies.onionphone.securemessenger.core.model.ProxyConfig
import ltechnologies.onionphone.securemessenger.core.proxy.SocksEndpointResolver
import org.signal.core.util.SignalSocksHolder

internal object SignalTor {
    fun socksProxy(config: ProxyConfig): Proxy {
        val host = SocksEndpointResolver.resolveReachableHost(config.host, config.port)
        return Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, config.port))
    }

    suspend fun <T> withSocks(
        proxy: ProxyConfig,
        dispatcher: CoroutineDispatcher,
        block: suspend () -> T,
    ): T = withContext(dispatcher) {
        val socks = socksProxy(proxy)
        try {
            SignalSocksHolder.set(socks)
            block()
        } finally {
            SignalSocksHolder.clear()
        }
    }

    fun <T> withSocksBlocking(
        proxy: ProxyConfig,
        dispatcher: CoroutineDispatcher,
        block: () -> T,
    ): T = kotlinx.coroutines.runBlocking(dispatcher) {
        withSocks(proxy, dispatcher, block)
    }
}
