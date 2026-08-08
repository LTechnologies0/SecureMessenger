package ltechnologies.onionphone.securemessenger.protocol.email

import jakarta.mail.Session
import jakarta.mail.Store
import jakarta.mail.Transport
import java.util.Properties
import ltechnologies.onionphone.securemessenger.core.model.ProxyConfig

class EmailSession(
    val accountId: String,
    val config: EmailAccountConfig,
    val proxy: ProxyConfig,
    val angusSession: Session? = null,
    val store: Store? = null,
    val transport: Transport? = null,
    val jmapClient: JmapClient? = null,
) {
    fun isConnected(): Boolean = when (config.storeKind) {
        EmailStoreKind.JMAP -> jmapClient?.isConnected() == true
        EmailStoreKind.IMAP, EmailStoreKind.POP3 -> store?.isConnected == true
    }

    fun close() {
        runCatching { store?.close() }
        runCatching { transport?.close() }
        jmapClient?.close()
    }

    companion object {
        fun openStoreAndTransport(
            accountId: String,
            config: EmailAccountConfig,
            proxy: ProxyConfig,
        ): EmailSession {
            val props = Properties()
            props["mail.store.protocol"] = when (config.storeKind) {
                EmailStoreKind.IMAP -> if (config.imapSecurity == MailSecurity.SSL) "imaps" else "imap"
                EmailStoreKind.POP3 -> if (config.pop3Security == MailSecurity.SSL) "pop3s" else "pop3"
                EmailStoreKind.JMAP -> error("JMAP uses Http client, not Store")
            }
            props["mail.transport.protocol"] =
                if (config.smtpSecurity == MailSecurity.SSL) "smtps" else "smtp"

            when (config.storeKind) {
                EmailStoreKind.IMAP -> {
                    val host = config.imapHost ?: error("Missing IMAP host")
                    val protocol = if (config.imapSecurity == MailSecurity.SSL) "imaps" else "imap"
                    EmailSocksProperties.applyStoreSecurity(
                        props, protocol, config.imapSecurity, host, config.imapPort,
                    )
                    EmailSocksProperties.applySocks(props, listOf(protocol, "imap", "imaps"), proxy)
                }
                EmailStoreKind.POP3 -> {
                    val host = config.pop3Host ?: error("Missing POP3 host")
                    val protocol = if (config.pop3Security == MailSecurity.SSL) "pop3s" else "pop3"
                    EmailSocksProperties.applyStoreSecurity(
                        props, protocol, config.pop3Security, host, config.pop3Port,
                    )
                    EmailSocksProperties.applySocks(props, listOf(protocol, "pop3", "pop3s"), proxy)
                }
                EmailStoreKind.JMAP -> Unit
            }

            val smtpHost = config.smtpHost ?: error("Missing SMTP host")
            val smtpProtocol = if (config.smtpSecurity == MailSecurity.SSL) "smtps" else "smtp"
            EmailSocksProperties.applyStoreSecurity(
                props, smtpProtocol, config.smtpSecurity, smtpHost, config.smtpPort,
            )
            EmailSocksProperties.applySocks(props, listOf(smtpProtocol, "smtp", "smtps"), proxy)
            props["mail.$smtpProtocol.auth"] = "true"
            props["mail.smtp.auth"] = "true"
            props["mail.smtps.auth"] = "true"

            val session = Session.getInstance(props)
            session.debug = false

            val storeProtocol = props.getProperty("mail.store.protocol")
            val store = session.getStore(storeProtocol)
            val storeHost = when (config.storeKind) {
                EmailStoreKind.IMAP -> config.imapHost!!
                EmailStoreKind.POP3 -> config.pop3Host!!
                EmailStoreKind.JMAP -> error("unreachable")
            }
            val storePort = when (config.storeKind) {
                EmailStoreKind.IMAP -> config.imapPort
                EmailStoreKind.POP3 -> config.pop3Port
                EmailStoreKind.JMAP -> error("unreachable")
            }
            store.connect(storeHost, storePort, config.email, config.password)

            val transport = session.getTransport(smtpProtocol)
            transport.connect(smtpHost, config.smtpPort, config.email, config.password)

            return EmailSession(
                accountId = accountId,
                config = config,
                proxy = proxy,
                angusSession = session,
                store = store,
                transport = transport,
            )
        }
    }
}
