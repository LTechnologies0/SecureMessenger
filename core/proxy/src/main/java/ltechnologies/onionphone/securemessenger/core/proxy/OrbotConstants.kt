package ltechnologies.onionphone.securemessenger.core.proxy

object OrbotConstants {
    const val PACKAGE_NAME = "org.torproject.android"
    const val ACTION_STATUS = "org.torproject.android.intent.action.STATUS"
    const val ACTION_START = "org.torproject.android.intent.action.START"
    const val EXTRA_STATUS = "org.torproject.android.intent.extra.STATUS"
    const val EXTRA_PACKAGE_NAME = "org.torproject.android.intent.extra.PACKAGE_NAME"
    const val EXTRA_SOCKS_PROXY = "org.torproject.android.intent.extra.SOCKS_PROXY"
    const val EXTRA_SOCKS_PROXY_HOST = "org.torproject.android.intent.extra.SOCKS_PROXY_HOST"
    const val EXTRA_SOCKS_PROXY_PORT = "org.torproject.android.intent.extra.SOCKS_PROXY_PORT"
    const val EXTRA_PROXY_PORT_SOCKS = "org.torproject.android.intent.extra.SOCKS_PROXY_PORT"
    const val STATUS_ON = "ON"
    const val STATUS_OFF = "OFF"
    const val STATUS_STARTING = "STARTING"
    const val STATUS_STOPPING = "STOPPING"
    const val SOCKS_HOST = "127.0.0.1"
    const val SOCKS_PORT = 9050
}
