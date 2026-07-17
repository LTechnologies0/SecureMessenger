package org.signal.core.util

import java.net.Proxy

/**
 * Process-wide Tor SOCKS5 proxy for Signal OkHttp clients.
 * Set around network calls so PushServiceSocket / WebSocket prefer SOCKS over Signal's TLS proxy.
 */
object SignalSocksHolder {
    @Volatile
    private var proxy: Proxy? = null

    @JvmStatic
    fun set(socks: Proxy?) {
        proxy = socks
    }

    @JvmStatic
    fun get(): Proxy? = proxy

    @JvmStatic
    fun clear() {
        proxy = null
    }
}
