package org.signal.core.util

import java.net.Proxy

/**
 * Process-wide Tor SOCKS5 proxy for Signal OkHttp clients.
 * Set around network calls so PushServiceSocket / WebSocket prefer SOCKS over Signal's TLS proxy.
 */
object SignalSocksHolder {
    @Volatile
    private var proxy: Proxy? = null

    @Volatile
    private var username: String? = null

    @Volatile
    private var password: String? = null

    @JvmStatic
    fun set(socks: Proxy?) {
        set(socks, null, null)
    }

    @JvmStatic
    fun set(socks: Proxy?, username: String?, password: String?) {
        proxy = socks
        this.username = username?.takeIf { it.isNotBlank() }
        this.password = password?.takeIf { it.isNotBlank() }
    }

    @JvmStatic
    fun get(): Proxy? = proxy

    @JvmStatic
    fun getUsername(): String? = username

    @JvmStatic
    fun getPassword(): String? = password

    @JvmStatic
    fun clear() {
        proxy = null
        username = null
        password = null
    }
}
