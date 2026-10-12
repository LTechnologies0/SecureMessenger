package ltechnologies.onionphone.securemessenger.protocol.irc

import io.netty.bootstrap.Bootstrap
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.EventLoopGroup
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.proxy.Socks4ProxyHandler
import io.netty.handler.proxy.Socks5ProxyHandler
import java.net.InetSocketAddress
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import org.kitteh.irc.client.library.Client
import org.kitteh.irc.client.library.defaults.feature.network.NettyConnection
import org.kitteh.irc.client.library.feature.network.ClientConnection
import org.kitteh.irc.client.library.feature.network.NetworkHandler
import org.kitteh.irc.client.library.feature.network.ProxyType
import org.kitteh.irc.client.library.feature.network.Resolver
import org.kitteh.irc.client.library.feature.sts.StsClientState
import ltechnologies.onionphone.securemessenger.core.model.ProxyConfig

/** Per-connection SOCKS user — Tor stream isolation without leaking IRC identity. */
internal fun ircIsolatedSocksUser(): String = "irc-${UUID.randomUUID()}"

internal fun ircTorSocksCredentials(proxy: ProxyConfig): Pair<String, String> =
    ircIsolatedSocksUser() to (proxy.password?.takeIf { it.isNotBlank() } ?: "x")

internal object IrcTorBlockingResolver : Resolver {
    override fun getAddress(host: String) =
        throw UnknownHostException("Tor required: DNS via SOCKS only")
}

/**
 * Kitteh's [NettyNetworkHandler] installs [Socks5ProxyHandler] without credentials.
 * This handler mirrors its connect path but authenticates to Tor for stream isolation.
 */
internal class IrcTorNetworkHandler(
    private val socksUser: String,
    private val socksPass: String,
) : NetworkHandler {

    private val resolverRef = AtomicReference<Resolver>(IrcTorBlockingResolver)

    override fun getResolver(): Resolver = resolverRef.get()

    override fun setResolver(resolver: Resolver) {
        resolverRef.set(resolver)
    }

    @Synchronized
    override fun connect(client: Client.WithManagement): ClientConnection {
        val stsMachine = client.stsMachine
        if (stsMachine.isPresent && !client.isSecureConnection) {
            val host = client.serverAddress.host
            val machine = stsMachine.get()
            machine.storageManager.getEntry(host).ifPresent { policy ->
                machine.setStsPolicy(policy)
                machine.setCurrentState(StsClientState.STS_POLICY_CACHED)
            }
        }

        val bootstrap = Bootstrap()
            .channel(NioSocketChannel::class.java)
            .group(sharedEventLoop)
            .handler(TorProxyInitializer(client, socksUser, socksPass))
            .option(ChannelOption.TCP_NODELAY, true)

        val bindAddress = client.bindAddress
        val host = client.serverAddress.host
        val remote = try {
            val resolved = resolverRef.get().getAddress(host)
            InetSocketAddress(resolved, client.serverAddress.port)
        } catch (_: UnknownHostException) {
            InetSocketAddress.createUnresolved(host, client.serverAddress.port)
        }

        val future = bootstrap.connect(remote, bindAddress)
        return NettyConnection(client, future) { }
    }

    private class TorProxyInitializer(
        private val client: Client.WithManagement,
        private val socksUser: String,
        private val socksPass: String,
    ) : ChannelInitializer<SocketChannel>() {
        override fun initChannel(ch: SocketChannel) {
            val proxyType = client.proxyType.orElse(null) ?: return
            val proxyHost = client.proxyAddress.orElse(null) ?: return
            val pipeline = ch.pipeline()
            val proxyEndpoint = InetSocketAddress(proxyHost.host, proxyHost.port)
            val handler: ChannelHandler = when (proxyType) {
                ProxyType.SOCKS_5 ->
                    Socks5ProxyHandler(proxyEndpoint, socksUser, socksPass)
                ProxyType.SOCKS_4 ->
                    Socks4ProxyHandler(proxyEndpoint, socksUser)
            }
            pipeline.addLast(handler)
        }
    }

    companion object {
        private val sharedEventLoop: EventLoopGroup = NioEventLoopGroup(1)

        fun create(proxy: ProxyConfig): IrcTorNetworkHandler {
            val (user, pass) = ircTorSocksCredentials(proxy)
            return IrcTorNetworkHandler(user, pass)
        }
    }
}
