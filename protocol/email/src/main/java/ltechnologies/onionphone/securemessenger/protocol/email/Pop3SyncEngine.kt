package ltechnologies.onionphone.securemessenger.protocol.email

import android.content.Context
import jakarta.mail.Folder
import jakarta.mail.Flags
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ltechnologies.onionphone.securemessenger.core.model.Conversation
import ltechnologies.onionphone.securemessenger.core.model.MessageDirection
import ltechnologies.onionphone.securemessenger.core.model.ProtocolId
import ltechnologies.onionphone.securemessenger.data.MessengerRepository
import timber.log.Timber

class Pop3SyncEngine(
    private val context: Context,
    private val repository: MessengerRepository,
) {
    private var pollJob: Job? = null

    fun start(scope: CoroutineScope, session: EmailSession, intervalMs: Long = 90_000L) {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                try {
                    sync(session)
                } catch (e: Exception) {
                    Timber.w(e, "POP3 sync failed")
                }
                delay(intervalMs)
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    suspend fun sync(session: EmailSession) {
        val store = session.store ?: return
        val folder = store.getFolder("INBOX")
        if (!folder.exists()) return
        folder.open(Folder.READ_WRITE)
        try {
            val count = folder.messageCount
            if (count <= 0) return
            val start = (count - 49).coerceAtLeast(1)
            val messages = folder.getMessages(start, count)
            for (mail in messages) {
                val mime = mail as? MimeMessage ?: continue
                val parsed = MimeMapper.parse(context, session.accountId, mime, session.config.email)
                val conversationId =
                    EmailThreading.conversationId(session.accountId, parsed.rootMessageId)
                val message = MimeMapper.toDomainMessage(
                    accountId = session.accountId,
                    conversationId = conversationId,
                    parsed = parsed,
                    ownEmail = session.config.email,
                )
                // Dedup via message id primary key upsert.
                repository.upsertMessage(message)
                val existing = repository.getConversation(conversationId)
                repository.upsertConversation(
                    Conversation(
                        id = conversationId,
                        protocol = ProtocolId.EMAIL,
                        accountId = session.accountId,
                        remoteId = parsed.rootMessageId,
                        title = parsed.subject,
                        lastMessagePreview = parsed.body.take(160),
                        lastMessageAt = parsed.timestamp,
                        unreadCount = if (message.direction == MessageDirection.INCOMING) {
                            (existing?.unreadCount ?: 0) + 1
                        } else {
                            existing?.unreadCount ?: 0
                        },
                    ),
                )
                if (!session.config.pop3LeaveOnServer) {
                    mime.setFlag(Flags.Flag.DELETED, true)
                }
            }
        } finally {
            runCatching { folder.close(!session.config.pop3LeaveOnServer) }
        }
    }
}
