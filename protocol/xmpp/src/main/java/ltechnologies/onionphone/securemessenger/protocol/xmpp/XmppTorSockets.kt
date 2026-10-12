package ltechnologies.onionphone.securemessenger.protocol.xmpp

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.UUID
import javax.net.SocketFactory
import org.jivesoftware.smack.ConnectionConfiguration
import org.jivesoftware.smack.proxy.ProxyInfo
import org.jivesoftware.smack.util.dns.DNSResolver
import org.jivesoftware.smack.util.rce.RemoteConnectionEndpointLookupFailure
import org.minidns.dnsname.DnsName
import org.minidns.record.SRV

/** SOCKS user that is not the JID — stream isolation without leaking the account identity. */
internal fun isolatedSocksUser(): String = "sm-${UUID.randomUUID()}"

/**
 * SocketFactory that connects through SOCKS using [InetSocketAddress.createUnresolved]
 * so the XMPP hostname is never resolved on-device.
 */
internal class SocksUnresolvedSocketFactory(
    socksHost: String,
    socksPort: Int,
) : SocketFactory() {
    private val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(socksHost, socksPort))

    override fun createSocket(): Socket = Socket(proxy)

    override fun createSocket(host: String, port: Int): Socket {
        val socket = Socket(proxy)
        socket.connect(InetSocketAddress.createUnresolved(host, port), CONNECT_TIMEOUT_MS)
        return socket
    }

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        createSocket(host, port)

    override fun createSocket(address: InetAddress, port: Int): Socket {
        val host = address.hostName?.takeIf { it.isNotBlank() } ?: address.hostAddress ?: "0.0.0.0"
        return createSocket(host, port)
    }

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int,
    ): Socket = createSocket(address, port)

    companion object {
        private const val CONNECT_TIMEOUT_MS = 30_000
    }
}

/**
 * Dummy resolver: never calls the system DNS.
 * Install with [org.jivesoftware.smack.util.DNSUtil.setDNSResolver] — Smack 4.5
 * removed [org.jivesoftware.smack.ConnectionConfiguration.Builder.setDnsResolver].
 */
internal class DummyTorDnsResolver : DNSResolver(false) {
    override fun lookupSrvRecords0(
        name: DnsName,
        failures: MutableList<RemoteConnectionEndpointLookupFailure>,
        dnssecMode: ConnectionConfiguration.DnssecMode,
    ): Collection<SRV> = emptyList()

    override fun lookupHostAddress0(
        name: DnsName,
        failures: MutableList<RemoteConnectionEndpointLookupFailure>,
        dnssecMode: ConnectionConfiguration.DnssecMode,
    ): MutableList<InetAddress> = mutableListOf(
        InetAddress.getByAddress(name.toString(), byteArrayOf(0, 0, 0, 0)),
    )
}

internal fun smackSocksProxyInfo(socksHost: String, socksPort: Int, username: String?, password: String?): ProxyInfo =
    ProxyInfo(
        ProxyInfo.ProxyType.SOCKS5,
        socksHost,
        socksPort,
        username ?: isolatedSocksUser(),
        password ?: "x",
    )
