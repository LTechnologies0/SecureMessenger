package ltechnologies.onionphone.securemessenger.protocol.email

import android.content.Context
import jakarta.mail.Address
import jakarta.mail.Message as MailMessage
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import java.io.File
import java.util.UUID
import ltechnologies.onionphone.securemessenger.core.model.Attachment
import ltechnologies.onionphone.securemessenger.core.model.AttachmentState
import ltechnologies.onionphone.securemessenger.core.model.DeliveryState
import ltechnologies.onionphone.securemessenger.core.model.Message
import ltechnologies.onionphone.securemessenger.core.model.MessageDirection
import ltechnologies.onionphone.securemessenger.core.model.MessageKind
import ltechnologies.onionphone.securemessenger.core.model.ProtocolId

object MimeMapper {
    const val MAX_ATTACHMENT_BYTES = 25L * 1024L * 1024L

    data class ParsedMail(
        val messageId: String,
        val rootMessageId: String,
        val subject: String,
        val body: String,
        val from: String,
        val to: List<String>,
        val cc: List<String>,
        val timestamp: Long,
        val inReplyTo: String?,
        val references: String?,
        val attachments: List<Attachment>,
    )

    fun parse(
        context: Context,
        accountId: String,
        mail: MailMessage,
        ownEmail: String,
    ): ParsedMail {
        val messageIdHeader = mail.getHeader("Message-ID")?.firstOrNull()
        val inReplyTo = mail.getHeader("In-Reply-To")?.firstOrNull()
        val references = mail.getHeader("References")?.firstOrNull()
        val messageId = EmailThreading.normalizeMessageId(
            messageIdHeader ?: "generated-${UUID.randomUUID()}",
        )
        val root = EmailThreading.rootMessageId(messageId, inReplyTo, references)
        val subject = mail.subject?.takeIf { it.isNotBlank() } ?: "(sans objet)"
        val from = mail.from?.firstOrNull()?.let { formatAddress(it) } ?: "unknown"
        val to = mail.getRecipients(MailMessage.RecipientType.TO)
            ?.map { formatAddress(it) }
            .orEmpty()
        val cc = mail.getRecipients(MailMessage.RecipientType.CC)
            ?.map { formatAddress(it) }
            .orEmpty()
        val timestamp = mail.sentDate?.time
            ?: mail.receivedDate?.time
            ?: System.currentTimeMillis()

        val textParts = mutableListOf<String>()
        val attachments = mutableListOf<Attachment>()
        collectParts(context, accountId, mail, textParts, attachments)

        return ParsedMail(
            messageId = messageId,
            rootMessageId = root,
            subject = subject,
            body = textParts.firstOrNull { it.isNotBlank() }.orEmpty().ifBlank { subject },
            from = from,
            to = to,
            cc = cc,
            timestamp = timestamp,
            inReplyTo = inReplyTo?.let { EmailThreading.normalizeMessageId(it) },
            references = references,
            attachments = attachments,
        )
    }

    fun toDomainMessage(
        accountId: String,
        conversationId: String,
        parsed: ParsedMail,
        ownEmail: String,
    ): Message {
        val fromAddr = EmailAddress.extract(parsed.from)
        val outgoing = fromAddr.equals(ownEmail, ignoreCase = true)
        val kind = when {
            parsed.attachments.any { it.mimeType.startsWith("image/") } -> MessageKind.IMAGE
            parsed.attachments.isNotEmpty() -> MessageKind.FILE
            else -> MessageKind.TEXT
        }
        return Message(
            id = "$accountId:msg:${parsed.messageId}",
            conversationId = conversationId,
            protocol = ProtocolId.EMAIL,
            body = parsed.body,
            timestamp = parsed.timestamp,
            direction = if (outgoing) MessageDirection.OUTGOING else MessageDirection.INCOMING,
            deliveryState = DeliveryState.DELIVERED,
            senderDisplayName = parsed.from,
            attachments = parsed.attachments,
            kind = kind,
            payloadJson = buildString {
                append('{')
                append("\"messageId\":\"").append(jsonEscape(parsed.messageId)).append("\",")
                append("\"subject\":\"").append(jsonEscape(parsed.subject)).append("\",")
                append("\"rootMessageId\":\"").append(jsonEscape(parsed.rootMessageId)).append('"')
                append('}')
            },
        )
    }

    fun buildMime(
        session: jakarta.mail.Session,
        from: String,
        to: List<String>,
        subject: String,
        body: String,
        inReplyTo: String? = null,
        references: String? = null,
        attachments: List<Attachment> = emptyList(),
    ): MimeMessage {
        val message = MimeMessage(session)
        message.setFrom(InternetAddress(from))
        message.setRecipients(
            MailMessage.RecipientType.TO,
            to.map { InternetAddress(it) }.toTypedArray(),
        )
        message.subject = subject
        message.sentDate = java.util.Date()
        if (!inReplyTo.isNullOrBlank()) {
            message.setHeader("In-Reply-To", "<$inReplyTo>")
        }
        if (!references.isNullOrBlank()) {
            message.setHeader("References", references)
        } else if (!inReplyTo.isNullOrBlank()) {
            message.setHeader("References", "<$inReplyTo>")
        }

        if (attachments.isEmpty()) {
            message.setText(body, "UTF-8")
        } else {
            val multipart = MimeMultipart()
            val textPart = MimeBodyPart()
            textPart.setText(body, "UTF-8")
            multipart.addBodyPart(textPart)
            for (attachment in attachments) {
                val path = attachment.localPath ?: continue
                val file = File(path)
                if (!file.isFile || file.length() > MAX_ATTACHMENT_BYTES) continue
                val part = MimeBodyPart()
                part.attachFile(file)
                part.fileName = attachment.fileName ?: file.name
                if (attachment.mimeType.isNotBlank()) {
                    part.setHeader("Content-Type", attachment.mimeType)
                }
                multipart.addBodyPart(part)
            }
            message.setContent(multipart)
        }
        message.saveChanges()
        return message
    }

    private fun collectParts(
        context: Context,
        accountId: String,
        part: Part,
        textParts: MutableList<String>,
        attachments: MutableList<Attachment>,
    ) {
        when {
            part.isMimeType("text/plain") -> {
                textParts += (part.content as? String).orEmpty()
            }
            part.isMimeType("text/html") && textParts.isEmpty() -> {
                val html = (part.content as? String).orEmpty()
                textParts += html.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
            }
            part.isMimeType("multipart/*") -> {
                val multipart = part.content as? Multipart ?: return
                for (i in 0 until multipart.count) {
                    collectParts(context, accountId, multipart.getBodyPart(i), textParts, attachments)
                }
            }
            Part.ATTACHMENT.equals(part.disposition, ignoreCase = true) ||
                !part.fileName.isNullOrBlank() -> {
                val size = part.size.toLong().coerceAtLeast(0L)
                if (size > MAX_ATTACHMENT_BYTES) return
                val dir = File(context.cacheDir, "email-attachments/$accountId").apply { mkdirs() }
                val safeName = (part.fileName ?: "attachment.bin").replace(Regex("[^A-Za-z0-9._-]"), "_")
                val out = File(dir, "${UUID.randomUUID()}-$safeName")
                part.inputStream.use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
                attachments += Attachment(
                    id = UUID.randomUUID().toString(),
                    mimeType = part.contentType?.substringBefore(';')?.trim() ?: "application/octet-stream",
                    fileName = part.fileName,
                    localPath = out.absolutePath,
                    sizeBytes = out.length(),
                    state = AttachmentState.READY,
                )
            }
        }
    }

    private fun formatAddress(address: Address): String {
        val internet = address as? InternetAddress
        return if (internet != null) {
            val personal = internet.personal?.takeIf { it.isNotBlank() }
            val email = internet.address
            if (personal != null) "$personal <$email>" else email
        } else {
            address.toString()
        }
    }

    private fun jsonEscape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")
}
