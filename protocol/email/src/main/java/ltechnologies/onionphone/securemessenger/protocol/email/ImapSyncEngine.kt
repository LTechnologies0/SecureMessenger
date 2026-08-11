package ltechnologies.onionphone.securemessenger.protocol.email

import android.content.Context
import jakarta.mail.Folder
import jakarta.mail.UIDFolder
import jakarta.mail.internet.MimeMessage
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ltechnologies.onionphone.securemessenger.core.model.Conversation
import ltechnologies.onionphone.securemessenger.core.model.ProtocolId
import ltechnologies.onionphone.securemessenger.core.security.EncryptedCredentialStore
import ltechnologies.onionphone.securemessenger.data.MessengerRepository
import timber.log.Timber

class ImapSyncEngine(
    private val context: Context,
    private val repository: MessengerRepository,
    private val credentialStore: EncryptedCredentialStore,
) {
    private var idleJob: Job? = null
    /** Serializes SELECT/IDLE vs sync/markSeen on the single Store connection. */
    private val folderMutex = Mutex()

    fun start(scope: CoroutineScope, session: EmailSession) {
        idleJob?.cancel()
        idleJob = scope.launch {
            var backoffMs = 2_000L
            while (isActive) {
                try {
                    folderMutex.withLock {
                        syncFolderLocked(session)
                        idleOnceLocked(session)
                    }
                    backoffMs = 2_000L
                } catch (e: Exception) {
                    Timber.w(e, "IMAP sync/IDLE interrupted")
                    delay(backoffMs)
                    backoffMs = min(backoffMs * 2, 60_000L)
                }
            }
        }
    }

    fun stop() {
        idleJob?.cancel()
        idleJob = null
    }

    suspend fun syncFolder(session: EmailSession) {
        folderMutex.withLock { syncFolderLocked(session) }
    }

    private suspend fun syncFolderLocked(session: EmailSession) {
        val store = session.store ?: return
        val folder = store.getFolder(session.config.folder)
        if (!folder.exists()) {
            Timber.w("IMAP folder missing: %s", session.config.folder)
            return
        }
        folder.open(Folder.READ_ONLY)
        try {
            val uidFolder = folder as? UIDFolder
            val lastUid = credentialStore.get(session.accountId, EmailCredentialKeys.LAST_IMAP_UID)
                ?.toLongOrNull() ?: 0L
            val messages = if (uidFolder != null && lastUid > 0L) {
                uidFolder.getMessagesByUID(lastUid + 1, UIDFolder.LASTUID)
            } else {
                val count = folder.messageCount
                if (count <= 0) emptyArray()
                else {
                    val start = (count - 99).coerceAtLeast(1)
                    folder.getMessages(start, count)
                }
            }
            var maxUid = lastUid
            for (mail in messages) {
                if (mail == null) continue
                persistMail(session, mail as? MimeMessage ?: continue)
                if (uidFolder != null) {
                    maxUid = maxOf(maxUid, uidFolder.getUID(mail))
                }
            }
            if (uidFolder != null && maxUid > lastUid) {
                credentialStore.put(
                    session.accountId,
                    EmailCredentialKeys.LAST_IMAP_UID,
                    maxUid.toString(),
                )
            }
        } finally {
            runCatching { folder.close(false) }
        }
    }

    private suspend fun idleOnceLocked(session: EmailSession) {
        val store = session.store ?: return
        val folder = store.getFolder(session.config.folder)
        folder.open(Folder.READ_ONLY)
        try {
            val idleFolder = folder as? org.eclipse.angus.mail.imap.IMAPFolder
            if (idleFolder == null) {
                delay(60_000)
                return
            }
            // IDLE blocks until server pushes or timeout; Angus respects timeout property.
            idleFolder.idle(true)
        } finally {
            runCatching { folder.close(false) }
        }
        // Pull new UIDs only after IDLE folder is closed (single SELECT).
        syncFolderLocked(session)
    }

    private suspend fun persistMail(session: EmailSession, mail: MimeMessage) {
        val parsed = MimeMapper.parse(context, session.accountId, mail, session.config.email)
        val conversationId = EmailThreading.conversationId(session.accountId, parsed.rootMessageId)
        val message = MimeMapper.toDomainMessage(
            accountId = session.accountId,
            conversationId = conversationId,
            parsed = parsed,
            ownEmail = session.config.email,
        )
        val alreadyPersisted = repository.getMessage(message.id) != null
        repository.upsertMessage(message)
        val existing = repository.getConversation(conversationId)
        val bumpUnread = !alreadyPersisted &&
            message.direction ==
            ltechnologies.onionphone.securemessenger.core.model.MessageDirection.INCOMING
        repository.upsertConversation(
            Conversation(
                id = conversationId,
                protocol = ProtocolId.EMAIL,
                accountId = session.accountId,
                remoteId = parsed.rootMessageId,
                title = parsed.subject,
                lastMessagePreview = parsed.body.take(160),
                lastMessageAt = parsed.timestamp,
                unreadCount = if (bumpUnread) {
                    (existing?.unreadCount ?: 0) + 1
                } else {
                    existing?.unreadCount ?: 0
                },
            ),
        )
        // Seen flag not written (folder opened READ_ONLY).
    }

    /**
     * Best-effort: open the mailbox READ_WRITE and set [Flags.Flag.SEEN] on messages whose
     * Message-ID matches [messageIds]. Returns how many flags were written.
     */
    suspend fun markSeen(session: EmailSession, messageIds: Collection<String>): Int {
        if (messageIds.isEmpty()) return 0
        return folderMutex.withLock {
            markSeenLocked(session, messageIds)
        }
    }

    private suspend fun markSeenLocked(session: EmailSession, messageIds: Collection<String>): Int {
        val store = session.store ?: return 0
        val normalized = messageIds.map { EmailThreading.normalizeMessageId(it) }.toSet()
        val folder = store.getFolder(session.config.folder)
        if (!folder.exists()) return 0
        folder.open(Folder.READ_WRITE)
        return try {
            var marked = 0
            val count = folder.messageCount
            if (count <= 0) return 0
            // Scan recent window — full-folder scan is too expensive for mark-read.
            val start = (count - 499).coerceAtLeast(1)
            val messages = folder.getMessages(start, count)
            for (mail in messages) {
                if (mail == null) continue
                val mime = mail as? MimeMessage ?: continue
                val mid = EmailThreading.normalizeMessageId(
                    mime.messageID?.takeIf { it.isNotBlank() } ?: continue,
                )
                if (mid !in normalized) continue
                mime.setFlag(jakarta.mail.Flags.Flag.SEEN, true)
                marked++
            }
            marked
        } finally {
            runCatching { folder.close(false) }
        }
    }
}
