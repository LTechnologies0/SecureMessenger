package ltechnologies.onionphone.securemessenger.protocol.email

import jakarta.mail.Session
import jakarta.mail.Store
import jakarta.mail.Transport
import java.util.Properties
import ltechnologies.onionphone.securemessenger.core.model.ProxyConfig
import ltechnologies.onionphone.securemessenger.core.network.ProxiedHttpClientFactory
import timber.log.Timber

class EmailSession(
    val accountId: String,
    val config: EmailAccountConfig,
    val proxy: ProxyConfig,
    val angusSession: Session? = null,
    val store: Store? = null,
    val transport: Transport? = null,
    val jmapClient: JmapClient? = null,
    val storeConnectHost: String? = null,
    val storePort: Int = 0,
    val smtpConnectHost: String? = null,
) {
    /** Set after a successful Angus [Store.connect]; Angus [Store.isConnected] is not reliable. */
    @Volatile
    var storeAuthenticated: Boolean = false
        private set

    @Volatile
    var smtpAuthenticated: Boolean = false
        private set

    fun isConnected(): Boolean = when (config.storeKind) {
        EmailStoreKind.JMAP -> jmapClient?.isConnected() == true
        EmailStoreKind.IMAP, EmailStoreKind.POP3 -> storeAuthenticated
    }

    fun invalidateStoreAuth() {
        storeAuthenticated = false
    }

    fun invalidateSmtpAuth() {
        smtpAuthenticated = false
    }

    fun reconnectStore(): Boolean {
        val store = this.store ?: return false
        if (storeAuthenticated) return true
        val host = storeConnectHost ?: return false
        return runCatching {
            store.connect(host, storePort, config.email, config.password)
            storeAuthenticated = true
            true
        }.getOrElse {
            storeAuthenticated = false
            false
        }
    }

    fun reconnectSmtp(): Boolean {
        val transport = this.transport ?: return false
        val host = smtpConnectHost ?: return false
        return runCatching {
            transport.connect(host, config.smtpPort, config.email, config.password)
            smtpAuthenticated = true
            true
        }.getOrElse {
            smtpAuthenticated = false
            false
        }
    }

    fun close() {
        storeAuthenticated = false
        smtpAuthenticated = false
        runCatching { store?.close() }
        runCatching { transport?.close() }
        jmapClient?.close()
    }

    companion object {
        fun openStoreAndTransport(
            accountId: String,
            config: EmailAccountConfig,
            proxy: ProxyConfig,
            httpFactory: ProxiedHttpClientFactory? = null,
        ): EmailSession {
            val props = Properties()
            props["mail.store.protocol"] = when (config.storeKind) {
                EmailStoreKind.IMAP -> if (config.imapSecurity == MailSecurity.SSL) "imaps" else "imap"
                EmailStoreKind.POP3 -> if (config.pop3Security == MailSecurity.SSL) "pop3s" else "pop3"
                EmailStoreKind.JMAP -> error("JMAP uses Http client, not Store")
            }
            props["mail.transport.protocol"] =
                if (config.smtpSecurity == MailSecurity.SSL) "smtps" else "smtp"

            val storeServerName: String
            val storeConnectHost: String
            val storePort: Int
            val storeProtocol: String
            val storeSecurity: MailSecurity

            when (config.storeKind) {
                EmailStoreKind.IMAP -> {
                    storeServerName = config.imapHost ?: error("Missing IMAP host")
                    storePort = config.imapPort
                    storeSecurity = config.imapSecurity
                    storeProtocol = if (storeSecurity == MailSecurity.SSL) "imaps" else "imap"
                    storeConnectHost = EmailSocksProperties.resolveConnectHost(
                        storeServerName, proxy, httpFactory,
                    ) ?: error("Missing IMAP connect host")
                    EmailSocksProperties.applyStoreSecurity(
                        props, storeProtocol, storeSecurity, storeServerName, storePort, storeConnectHost,
                    )
                    EmailSocksProperties.applySocks(props, listOf(storeProtocol, "imap", "imaps"), proxy)
                }
                EmailStoreKind.POP3 -> {
                    storeServerName = config.pop3Host ?: error("Missing POP3 host")
                    storePort = config.pop3Port
                    storeSecurity = config.pop3Security
                    storeProtocol = if (storeSecurity == MailSecurity.SSL) "pop3s" else "pop3"
                    storeConnectHost = EmailSocksProperties.resolveConnectHost(
                        storeServerName, proxy, httpFactory,
                    ) ?: error("Missing POP3 connect host")
                    EmailSocksProperties.applyStoreSecurity(
                        props, storeProtocol, storeSecurity, storeServerName, storePort, storeConnectHost,
                    )
                    EmailSocksProperties.applySocks(props, listOf(storeProtocol, "pop3", "pop3s"), proxy)
                }
                EmailStoreKind.JMAP -> error("unreachable")
            }

            val smtpServerName = config.smtpHost ?: error("Missing SMTP host")
            val smtpProtocol = if (config.smtpSecurity == MailSecurity.SSL) "smtps" else "smtp"
            EmailSocksProperties.applySocks(props, listOf(smtpProtocol, "smtp", "smtps"), proxy)
            props["mail.$smtpProtocol.auth"] = "true"
            props["mail.smtp.auth"] = "true"
            props["mail.smtps.auth"] = "true"

            val session = Session.getInstance(props)
            session.debug = false

            val store = session.getStore(props.getProperty("mail.store.protocol"))
            store.connect(storeConnectHost, storePort, config.email, config.password)

            // Resolve SMTP via DoH after store is up; DoH failure must not block inbox.
            val smtpConnectHost = EmailSocksProperties.resolveConnectHost(
                smtpServerName,
                proxy,
                httpFactory,
                strict = false,
            )
            if (smtpConnectHost != null) {
                EmailSocksProperties.applyStoreSecurity(
                    props,
                    smtpProtocol,
                    config.smtpSecurity,
                    smtpServerName,
                    config.smtpPort,
                    smtpConnectHost,
                )
            } else {
                Timber.w("SMTP DoH skipped — outbound mail unavailable until retry")
            }

            val transport = session.getTransport(smtpProtocol)
            var smtpOk = false
            if (smtpConnectHost != null) {
                runCatching {
                    // Reuse the Tor-resolved IP — never pass the original hostname (local DNS leak).
                    transport.connect(smtpConnectHost, config.smtpPort, config.email, config.password)
                    smtpOk = true
                }.onFailure { e ->
                    Timber.w(e, "SMTP connect failed (inbox still available)")
                }
            }

            return EmailSession(
                accountId = accountId,
                config = config,
                proxy = proxy,
                angusSession = session,
                store = store,
                transport = transport,
                storeConnectHost = storeConnectHost,
                storePort = storePort,
                smtpConnectHost = smtpConnectHost,
            ).also { opened ->
                opened.storeAuthenticated = true
                opened.smtpAuthenticated = smtpOk
            }
        }
    }
}
