package ltechnologies.onionphone.securemessenger.protocol.email

import jakarta.mail.Transport
import jakarta.mail.internet.MimeMessage
import ltechnologies.onionphone.securemessenger.core.model.Attachment
import ltechnologies.onionphone.securemessenger.core.model.SendResult
import timber.log.Timber

class SmtpSender {
    fun send(
        session: EmailSession,
        to: List<String>,
        subject: String,
        body: String,
        cc: List<String> = emptyList(),
        bcc: List<String> = emptyList(),
        inReplyTo: String? = null,
        references: String? = null,
        attachments: List<Attachment> = emptyList(),
    ): SendResult {
        val angus = session.angusSession
            ?: return SendResult.Failure("SMTP session unavailable")
        val transport: Transport = session.transport
            ?: return SendResult.Failure("SMTP transport unavailable")
        return try {
            val recipients = to.map { EmailAddress.requireValid(it) }
            if (recipients.isEmpty()) return SendResult.Failure("No recipients")
            val ccRecipients = cc.map { EmailAddress.requireValid(it) }
            val bccRecipients = bcc.map { EmailAddress.requireValid(it) }
            val mime: MimeMessage = MimeMapper.buildMime(
                session = angus,
                from = session.config.email,
                to = recipients,
                subject = subject,
                body = body,
                cc = ccRecipients,
                bcc = bccRecipients,
                inReplyTo = inReplyTo,
                references = references,
                attachments = attachments,
            )
            if (!session.smtpAuthenticated && !session.reconnectSmtp()) {
                return SendResult.Failure("SMTP non connecté (réessayez ou vérifiez le serveur sortant)")
            }
            transport.sendMessage(mime, mime.allRecipients)
            val messageId = mime.messageID?.let { EmailThreading.normalizeMessageId(it) }
                ?: "sent-${System.currentTimeMillis()}"
            SendResult.Success(messageId)
        } catch (e: IllegalArgumentException) {
            SendResult.Failure(e.message ?: "Invalid recipient")
        } catch (e: Exception) {
            session.invalidateSmtpAuth()
            Timber.w(e, "SMTP send failed")
            SendResult.Failure(e.message ?: "SMTP send failed")
        }
    }
}
