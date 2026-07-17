package ltechnologies.onionphone.securemessenger.protocol.xmpp

import android.content.Context
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ltechnologies.onionphone.securemessenger.core.model.AccountCredentials
import ltechnologies.onionphone.securemessenger.core.model.ProtocolId
import ltechnologies.onionphone.securemessenger.core.model.ProxyConfig
import ltechnologies.onionphone.securemessenger.core.proxy.SocksEndpointResolver
import ltechnologies.onionphone.securemessenger.core.model.RegistrationResult
import org.jivesoftware.smack.ConnectionConfiguration
import org.jivesoftware.smack.XMPPException
import org.jivesoftware.smack.proxy.ProxyInfo
import org.jivesoftware.smack.tcp.XMPPTCPConnection
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration
import org.jivesoftware.smackx.iqregister.AccountManager
import org.jxmpp.jid.parts.Localpart

/**
 * XEP-0077 In-Band Registration via Smack's [AccountManager]. Registration happens on a
 * connection that is connected but not yet authenticated (no [XMPPTCPConnection.login]).
 */
internal class XmppRegistration(private val context: Context) {

    data class Requirements(
        val supported: Boolean,
        val requiredAttributes: Set<String>,
        val instructions: String?,
    )

    private data class PendingRegistration(val server: String, val username: String, val password: String)

    /** Keyed by our own sessionId, since the UI only knows about that id. */
    private val pending = mutableMapOf<String, PendingRegistration>()

    fun rememberPending(sessionId: String, server: String, username: String, password: String) {
        pending[sessionId] = PendingRegistration(server, username, password)
    }

    fun consumePending(sessionId: String): Triple<String, String, String>? =
        pending.remove(sessionId)?.let { Triple(it.server, it.username, it.password) }

    suspend fun fetchRequirements(server: String, proxy: ProxyConfig): Result<Requirements> =
        withContext(Dispatchers.IO) {
            runCatching {
                XmppInitializer.ensureInitialized(context)
                val conn = openProbeConnection(server, proxy)
                try {
                    val accountManager = AccountManager.getInstance(conn)
                    if (!accountManager.isSupported()) {
                        Requirements(supported = false, requiredAttributes = emptySet(), instructions = null)
                    } else {
                        val attrs = runCatching { accountManager.getAccountAttributes() }
                            .getOrDefault(emptySet())
                            .minus(setOf("username", "password"))
                        val instructions = runCatching { accountManager.getAccountInstructions() }.getOrNull()
                        Requirements(supported = true, requiredAttributes = attrs, instructions = instructions)
                    }
                } finally {
                    conn.disconnect()
                }
            }
        }

    suspend fun register(
        server: String,
        username: String,
        password: String,
        extraAttributes: Map<String, String>,
        proxy: ProxyConfig,
    ): RegistrationResult = withContext(Dispatchers.IO) {
        runCatching {
            XmppInitializer.ensureInitialized(context)
            val conn = openProbeConnection(server, proxy)
            try {
                val accountManager = AccountManager.getInstance(conn)
                val localpart = Localpart.from(username)
                if (extraAttributes.isEmpty()) {
                    accountManager.createAccount(localpart, password)
                } else {
                    accountManager.createAccount(localpart, password, extraAttributes)
                }
                RegistrationResult.Success(
                    AccountCredentials(
                        protocol = ProtocolId.XMPP,
                        accountId = UUID.randomUUID().toString(),
                        displayName = "$username@$server",
                        secrets = mapOf(
                            "jid" to "$username@$server",
                            "password" to password,
                            "server" to server,
                        ),
                    ),
                )
            } finally {
                conn.disconnect()
            }
        }.getOrElse { e -> RegistrationResult.Failure(describeError(e)) }
    }

    private fun openProbeConnection(server: String, proxy: ProxyConfig): XMPPTCPConnection {
        val socksHost = SocksEndpointResolver.resolveReachableHost(proxy.host, proxy.port)
        try {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(socksHost, proxy.port), 3_000)
            }
        } catch (e: Exception) {
            throw IllegalStateException(
                "Tor requis : SOCKS $socksHost:${proxy.port} injoignable — démarrez Orbot ou InviZible",
                e,
            )
        }
        // See SmackClientFacade.connect() — Smack's SOCKS5 client always offers both
        // no-auth and username/password methods, and Tor's SocksPort commonly picks the
        // latter for stream isolation. A non-null value must always be present or the
        // handshake fails before ever sending the destination host.
        val proxyInfo = ProxyInfo(
            ProxyInfo.ProxyType.SOCKS5,
            socksHost,
            proxy.port,
            proxy.username ?: server,
            proxy.password ?: "x",
        )
        val builder = XMPPTCPConnectionConfiguration.builder()
            .setXmppDomain(server)
            .setHost(server)
            .setPort(5222)
            .setSecurityMode(ConnectionConfiguration.SecurityMode.required)
            .setProxyInfo(proxyInfo)
        val conn = XMPPTCPConnection(builder.build())
        conn.connect()
        return conn
    }

    private fun describeError(e: Throwable): String = when (e) {
        is XMPPException.XMPPErrorException -> e.stanzaError?.descriptiveText
            ?: e.message
            ?: "XMPP registration failed"
        else -> e.message ?: "XMPP registration failed"
    }
}
