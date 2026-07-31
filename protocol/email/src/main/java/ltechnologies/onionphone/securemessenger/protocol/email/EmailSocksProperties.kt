package ltechnologies.onionphone.securemessenger.protocol.email

import java.security.MessageDigest
import java.util.Properties
import ltechnologies.onionphone.securemessenger.core.model.ProxyConfig
import ltechnologies.onionphone.securemessenger.core.proxy.SocksEndpointResolver

/**
 * Applies Tor SOCKS5 (or clearnet) properties for Angus Mail IMAP/SMTP/POP3 sessions.
 */
object EmailSocksProperties {
    fun applySocks(
        props: Properties,
        protocols: List<String>,
        proxy: ProxyConfig,
    ) {
        if (!proxy.torRequired) return
        val socksHost = SocksEndpointResolver.resolveReachableHost(proxy.host, proxy.port)
        for (protocol in protocols) {
            props["mail.$protocol.socks.host"] = socksHost
            props["mail.$protocol.socks.port"] = proxy.port.toString()
        }
    }

    fun applyStoreSecurity(
        props: Properties,
        protocol: String,
        security: MailSecurity,
        host: String,
        port: Int,
    ) {
        props["mail.$protocol.host"] = host
        props["mail.$protocol.port"] = port.toString()
        when (security) {
            MailSecurity.SSL -> {
                props["mail.$protocol.ssl.enable"] = "true"
                props["mail.$protocol.starttls.enable"] = "false"
                props["mail.$protocol.socketFactory.class"] =
                    "javax.net.ssl.SSLSocketFactory"
                props["mail.$protocol.socketFactory.fallback"] = "false"
                props["mail.$protocol.socketFactory.port"] = port.toString()
            }
            MailSecurity.STARTTLS -> {
                props["mail.$protocol.ssl.enable"] = "false"
                props["mail.$protocol.starttls.enable"] = "true"
                props["mail.$protocol.starttls.required"] = "true"
            }
            MailSecurity.NONE -> {
                props["mail.$protocol.ssl.enable"] = "false"
                props["mail.$protocol.starttls.enable"] = "false"
            }
        }
        props["mail.$protocol.connectiontimeout"] = "30000"
        props["mail.$protocol.timeout"] = "60000"
    }
}

object EmailThreading {
    fun conversationId(accountId: String, rootMessageId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(rootMessageId.trim().lowercase().toByteArray(Charsets.UTF_8))
        val hex = digest.take(16).joinToString("") { "%02x".format(it) }
        return "$accountId:thread:$hex"
    }

    fun mailboxConversationId(accountId: String, peerEmail: String): String {
        val peer = EmailAddress.normalize(peerEmail)
        return "$accountId:mailbox:$peer"
    }

    fun rootMessageId(
        messageId: String?,
        inReplyTo: String?,
        references: String?,
    ): String {
        val refs = references
            ?.split(Regex("\\s+"))
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        if (refs.isNotEmpty()) return normalizeMessageId(refs.first())
        if (!inReplyTo.isNullOrBlank()) return normalizeMessageId(inReplyTo)
        if (!messageId.isNullOrBlank()) return normalizeMessageId(messageId)
        return "orphan-${System.nanoTime()}"
    }

    fun normalizeMessageId(raw: String): String {
        var value = raw.trim()
        if (value.startsWith("<") && value.endsWith(">")) {
            value = value.substring(1, value.length - 1)
        }
        return value.lowercase()
    }
}
