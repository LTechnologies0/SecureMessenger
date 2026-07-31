package ltechnologies.onionphone.securemessenger.protocol.email

/**
 * Credential keys persisted via EncryptedCredentialStore for EMAIL accounts.
 */
object EmailCredentialKeys {
    const val EMAIL = "email"
    const val PASSWORD = "password"
    const val STORE_KIND = "storeKind"
    const val FOLDER = "folder"

    const val IMAP_HOST = "imapHost"
    const val IMAP_PORT = "imapPort"
    const val IMAP_SECURITY = "imapSecurity"

    const val POP3_HOST = "pop3Host"
    const val POP3_PORT = "pop3Port"
    const val POP3_SECURITY = "pop3Security"
    const val POP3_LEAVE_ON_SERVER = "pop3LeaveOnServer"

    const val SMTP_HOST = "smtpHost"
    const val SMTP_PORT = "smtpPort"
    const val SMTP_SECURITY = "smtpSecurity"

    const val JMAP_SESSION_URL = "jmapSessionUrl"

    const val LAST_IMAP_UID = "lastImapUid"
}

enum class EmailStoreKind {
    IMAP,
    POP3,
    JMAP,
    ;

    companion object {
        fun fromStored(raw: String?): EmailStoreKind =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: IMAP
    }
}

enum class MailSecurity {
    SSL,
    STARTTLS,
    NONE,
    ;

    companion object {
        fun fromStored(raw: String?): MailSecurity =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: SSL
    }
}

data class EmailAccountConfig(
    val email: String,
    val password: String,
    val storeKind: EmailStoreKind,
    val folder: String = "INBOX",
    val imapHost: String? = null,
    val imapPort: Int = 993,
    val imapSecurity: MailSecurity = MailSecurity.SSL,
    val pop3Host: String? = null,
    val pop3Port: Int = 995,
    val pop3Security: MailSecurity = MailSecurity.SSL,
    val pop3LeaveOnServer: Boolean = true,
    val smtpHost: String? = null,
    val smtpPort: Int = 465,
    val smtpSecurity: MailSecurity = MailSecurity.SSL,
    val jmapSessionUrl: String? = null,
) {
    companion object {
        fun fromSecrets(secrets: Map<String, String>): EmailAccountConfig? {
            val email = secrets[EmailCredentialKeys.EMAIL]?.trim()?.lowercase().orEmpty()
            val password = secrets[EmailCredentialKeys.PASSWORD].orEmpty()
            if (email.isBlank() || password.isBlank()) return null
            return EmailAccountConfig(
                email = email,
                password = password,
                storeKind = EmailStoreKind.fromStored(secrets[EmailCredentialKeys.STORE_KIND]),
                folder = secrets[EmailCredentialKeys.FOLDER]?.ifBlank { null } ?: "INBOX",
                imapHost = secrets[EmailCredentialKeys.IMAP_HOST]?.ifBlank { null },
                imapPort = secrets[EmailCredentialKeys.IMAP_PORT]?.toIntOrNull() ?: 993,
                imapSecurity = MailSecurity.fromStored(secrets[EmailCredentialKeys.IMAP_SECURITY]),
                pop3Host = secrets[EmailCredentialKeys.POP3_HOST]?.ifBlank { null },
                pop3Port = secrets[EmailCredentialKeys.POP3_PORT]?.toIntOrNull() ?: 995,
                pop3Security = MailSecurity.fromStored(secrets[EmailCredentialKeys.POP3_SECURITY]),
                pop3LeaveOnServer = secrets[EmailCredentialKeys.POP3_LEAVE_ON_SERVER]
                    ?.equals("true", ignoreCase = true) != false,
                smtpHost = secrets[EmailCredentialKeys.SMTP_HOST]?.ifBlank { null },
                smtpPort = secrets[EmailCredentialKeys.SMTP_PORT]?.toIntOrNull() ?: 465,
                smtpSecurity = MailSecurity.fromStored(secrets[EmailCredentialKeys.SMTP_SECURITY]),
                jmapSessionUrl = secrets[EmailCredentialKeys.JMAP_SESSION_URL]?.ifBlank { null },
            )
        }
    }

    fun toSecrets(): Map<String, String> = buildMap {
        put(EmailCredentialKeys.EMAIL, email)
        put(EmailCredentialKeys.PASSWORD, password)
        put(EmailCredentialKeys.STORE_KIND, storeKind.name)
        put(EmailCredentialKeys.FOLDER, folder)
        imapHost?.let { put(EmailCredentialKeys.IMAP_HOST, it) }
        put(EmailCredentialKeys.IMAP_PORT, imapPort.toString())
        put(EmailCredentialKeys.IMAP_SECURITY, imapSecurity.name)
        pop3Host?.let { put(EmailCredentialKeys.POP3_HOST, it) }
        put(EmailCredentialKeys.POP3_PORT, pop3Port.toString())
        put(EmailCredentialKeys.POP3_SECURITY, pop3Security.name)
        put(EmailCredentialKeys.POP3_LEAVE_ON_SERVER, pop3LeaveOnServer.toString())
        smtpHost?.let { put(EmailCredentialKeys.SMTP_HOST, it) }
        put(EmailCredentialKeys.SMTP_PORT, smtpPort.toString())
        put(EmailCredentialKeys.SMTP_SECURITY, smtpSecurity.name)
        jmapSessionUrl?.let { put(EmailCredentialKeys.JMAP_SESSION_URL, it) }
    }
}
