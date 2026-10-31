package ltechnologies.onionphone.securemessenger.protocol.email

import android.content.Context
import jakarta.mail.Flags
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
                    if (!session.reconnectStore()) {
                        delay(backoffMs)
                        backoffMs = min(backoffMs * 2, 60_000L)
                        continue
                    }
                    // Hold folderMutex for sync + IDLE — one Angus Store, one open folder.
                    folderMutex.withLock {
                        detectSpecialFoldersLocked(session)
                        syncFolderLocked(session)
                        syncSentFolderLocked(session)
                        idleOnceLocked(session)
                    }
                    backoffMs = 2_000L
                } catch (e: Exception) {
                    session.invalidateStoreAuth()
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
        folderMutex.withLock {
            detectSpecialFoldersLocked(session)
            syncFolderLocked(session)
            syncSentFolderLocked(session)
        }
    }

    /**
     * Bounded progressive backfill: fetch up to [chunkSize] older messages than the current
     * oldest watermark (never unbounded full archive).
     */
    suspend fun backfillOlder(session: EmailSession, chunkSize: Int = 50): Int {
        return folderMutex.withLock {
            val before = credentialStore.get(session.accountId, EmailCredentialKeys.OLDEST_IMAP_UID)
                ?.toLongOrNull()
                ?: credentialStore.get(session.accountId, EmailCredentialKeys.LAST_IMAP_UID)
                    ?.toLongOrNull()
                ?: 0L
            val (messages, minUid) = ImapMailboxOps.fetchOlderChunk(
                session = session,
                folderName = session.config.folder,
                beforeUidExclusive = before,
                chunkSize = chunkSize,
            )
            var persisted = 0
            for (mail in messages) {
                persistMail(session, mail)
                persisted++
            }
            val newOldest = minUid
                ?: messages.mapNotNull { mail ->
                    runCatching {
                        (session.store?.getFolder(session.config.folder) as? UIDFolder)?.getUID(mail)
                    }.getOrNull()
                }.minOrNull()
            if (newOldest != null && newOldest > 0L) {
                val previous = credentialStore.get(session.accountId, EmailCredentialKeys.OLDEST_IMAP_UID)
                    ?.toLongOrNull()
                if (previous == null || newOldest < previous) {
                    credentialStore.put(
                        session.accountId,
                        EmailCredentialKeys.OLDEST_IMAP_UID,
                        newOldest.toString(),
                    )
                }
            }
            persisted
        }
    }

    fun ensureSentFolderDetected(session: EmailSession): String? =
        ensureFolder(
            session,
            EmailCredentialKeys.SENT_FOLDER,
            session.config.sentFolder,
            ImapFolderNames::detectSentFolder,
        )

    fun ensureDraftsFolderDetected(session: EmailSession): String? =
        ensureFolder(
            session,
            EmailCredentialKeys.DRAFTS_FOLDER,
            session.config.draftsFolder,
            ImapFolderNames::detectDraftsFolder,
        )

    fun ensureTrashFolderDetected(session: EmailSession): String? =
        ensureFolder(
            session,
            EmailCredentialKeys.TRASH_FOLDER,
            session.config.trashFolder,
            ImapFolderNames::detectTrashFolder,
        )

    fun ensureArchiveFolderDetected(session: EmailSession): String? =
        ensureFolder(
            session,
            EmailCredentialKeys.ARCHIVE_FOLDER,
            session.config.archiveFolder,
            ImapFolderNames::detectArchiveFolder,
        )

    fun ensureJunkFolderDetected(session: EmailSession): String? =
        ensureFolder(
            session,
            EmailCredentialKeys.JUNK_FOLDER,
            session.config.junkFolder,
            ImapFolderNames::detectJunkFolder,
        )

    private fun ensureFolder(
        session: EmailSession,
        key: String,
        existingConfig: String?,
        detect: (jakarta.mail.Store) -> String?,
    ): String? {
        val store = session.store ?: return null
        val existing = existingConfig
            ?: credentialStore.get(session.accountId, key)?.ifBlank { null }
        if (existing != null) return existing
        val detected = detect(store) ?: return null
        credentialStore.put(session.accountId, key, detected)
        return detected
    }

    private fun detectSpecialFoldersLocked(session: EmailSession) {
        ensureSentFolderDetected(session)
        ensureDraftsFolderDetected(session)
        ensureTrashFolderDetected(session)
        ensureArchiveFolderDetected(session)
        ensureJunkFolderDetected(session)
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
            var minUid: Long? = null
            for (mail in messages) {
                if (mail == null) continue
                persistMail(session, mail as? MimeMessage ?: continue)
                if (uidFolder != null) {
                    val uid = uidFolder.getUID(mail)
                    maxUid = maxOf(maxUid, uid)
                    minUid = minOf(minUid ?: uid, uid)
                }
            }
            if (uidFolder != null && maxUid > lastUid) {
                credentialStore.put(
                    session.accountId,
                    EmailCredentialKeys.LAST_IMAP_UID,
                    maxUid.toString(),
                )
            }
            // Seed oldest watermark on first sync so progressive backfill has a ceiling.
            if (uidFolder != null && minUid != null) {
                val existingOldest = credentialStore.get(
                    session.accountId,
                    EmailCredentialKeys.OLDEST_IMAP_UID,
                )?.toLongOrNull()
                if (existingOldest == null) {
                    credentialStore.put(
                        session.accountId,
                        EmailCredentialKeys.OLDEST_IMAP_UID,
                        minUid.toString(),
                    )
                }
            }
        } finally {
            runCatching { folder.close(false) }
        }
    }

    /** Caller must hold [folderMutex]. Syncs recent Sent mail (read-only, no IDLE on Sent). */
    private suspend fun syncSentFolderLocked(session: EmailSession) {
        val sentName = ensureSentFolderDetected(session) ?: return
        val store = session.store ?: return
        val folder = store.getFolder(sentName)
        if (!folder.exists()) {
            Timber.w("IMAP Sent folder missing: %s", sentName)
            return
        }
        folder.open(Folder.READ_ONLY)
        try {
            val uidFolder = folder as? UIDFolder
            val lastUid = credentialStore.get(session.accountId, EmailCredentialKeys.LAST_IMAP_SENT_UID)
                ?.toLongOrNull() ?: 0L
            val messages = if (uidFolder != null && lastUid > 0L) {
                uidFolder.getMessagesByUID(lastUid + 1, UIDFolder.LASTUID)
            } else {
                val count = folder.messageCount
                if (count <= 0) emptyArray()
                else {
                    val start = (count - 49).coerceAtLeast(1)
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
                    EmailCredentialKeys.LAST_IMAP_SENT_UID,
                    maxUid.toString(),
                )
            }
        } finally {
            runCatching { folder.close(false) }
        }
    }

    /** Caller must hold [folderMutex]. */
    private suspend fun idleOnceLocked(session: EmailSession) {
        if (!session.reconnectStore()) return
        val store = session.store ?: return
        val folder = store.getFolder(session.config.folder)
        folder.open(Folder.READ_ONLY)
        try {
            val idleFolder = folder as? org.eclipse.angus.mail.imap.IMAPFolder
            if (idleFolder == null) {
                delay(15_000)
                return
            }
            // IMAPFolder.idle(boolean once): true returns after one notification.
            idleFolder.idle(true)
        } finally {
            runCatching { folder.close(false) }
        }
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
        val peer = MimeMapper.peerAddress(parsed, session.config.email)
        repository.upsertConversation(
            Conversation(
                id = conversationId,
                protocol = ProtocolId.EMAIL,
                accountId = session.accountId,
                remoteId = peer,
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

    suspend fun setFlagged(session: EmailSession, messageIds: Collection<String>, flagged: Boolean): Int =
        folderMutex.withLock {
            ImapMailboxOps.setFlagsByMessageIds(
                session,
                session.config.folder,
                messageIds,
                Flags.Flag.FLAGGED,
                flagged,
            )
        }

    suspend fun markAnswered(session: EmailSession, messageIds: Collection<String>): Int =
        folderMutex.withLock {
            ImapMailboxOps.setFlagsByMessageIds(
                session,
                session.config.folder,
                messageIds,
                Flags.Flag.ANSWERED,
                true,
            )
        }

    suspend fun trashMessages(session: EmailSession, messageIds: Collection<String>): Int =
        folderMutex.withLock {
            val trash = ensureTrashFolderDetected(session) ?: return@withLock 0
            ImapMailboxOps.moveByMessageIds(
                session,
                session.config.folder,
                trash,
                messageIds,
            )
        }

    suspend fun moveMessages(
        session: EmailSession,
        messageIds: Collection<String>,
        destFolder: String,
    ): Int = folderMutex.withLock {
        ImapMailboxOps.moveByMessageIds(
            session,
            session.config.folder,
            destFolder,
            messageIds,
        )
    }

    suspend fun saveDraft(
        session: EmailSession,
        mime: MimeMessage,
    ): Boolean = folderMutex.withLock {
        val drafts = ensureDraftsFolderDetected(session) ?: return@withLock false
        ImapMailboxOps.appendMime(
            session,
            drafts,
            mime,
            Flags(Flags.Flag.DRAFT),
        )
    }

    suspend fun search(
        session: EmailSession,
        query: String,
        folderName: String? = null,
        limit: Int = 50,
    ): List<ImapMailboxOps.SearchHit> = folderMutex.withLock {
        ImapMailboxOps.search(
            session,
            folderName ?: session.config.folder,
            query,
            limit,
        )
    }

    suspend fun listMailboxes(session: EmailSession): List<ImapFolderNames.MailboxInfo> =
        folderMutex.withLock {
            val store = session.store ?: return@withLock emptyList()
            if (!session.reconnectStore()) return@withLock emptyList()
            ImapFolderNames.listMailboxes(store)
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
                mime.setFlag(Flags.Flag.SEEN, true)
                marked++
            }
            marked
        } finally {
            runCatching { folder.close(false) }
        }
    }
}
