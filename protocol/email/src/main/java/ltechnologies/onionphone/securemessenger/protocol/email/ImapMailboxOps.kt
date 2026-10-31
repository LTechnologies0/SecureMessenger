package ltechnologies.onionphone.securemessenger.protocol.email

import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.UIDFolder
import jakarta.mail.internet.MimeMessage
import jakarta.mail.search.BodyTerm
import jakarta.mail.search.FromStringTerm
import jakarta.mail.search.OrTerm
import jakarta.mail.search.SearchTerm
import jakarta.mail.search.SubjectTerm
import timber.log.Timber

/**
 * IMAP mailbox mutations shared by [ImapSyncEngine] / [EmailProtocol]:
 * APPEND (Sent/Drafts), COPY+delete move, STORE flags, SEARCH.
 */
object ImapMailboxOps {
    data class SearchHit(
        val uid: Long?,
        val messageId: String?,
        val subject: String?,
        val from: String?,
    )

    fun appendMime(
        session: EmailSession,
        folderName: String,
        mime: MimeMessage,
        flags: Flags? = null,
    ): Boolean {
        val store = session.store ?: return false
        if (!session.reconnectStore()) return false
        val folder = store.getFolder(folderName)
        if (!folder.exists()) {
            Timber.w("APPEND target missing: %s", folderName)
            return false
        }
        folder.open(Folder.READ_WRITE)
        return try {
            folder.appendMessages(arrayOf(mime))
            if (flags != null) {
                // Best-effort: re-open recent and set flags on last message.
                val count = folder.messageCount
                if (count > 0) {
                    val last = folder.getMessage(count)
                    last.setFlags(flags, true)
                }
            }
            true
        } catch (e: Exception) {
            Timber.w(e, "IMAP APPEND failed → %s", folderName)
            false
        } finally {
            runCatching { folder.close(false) }
        }
    }

    /**
     * Moves messages matched by Message-ID from [sourceFolder] into [destFolder]
     * (COPY then \\Deleted; expunge on close when [expunge] is true).
     */
    fun moveByMessageIds(
        session: EmailSession,
        sourceFolder: String,
        destFolder: String,
        messageIds: Collection<String>,
        expunge: Boolean = true,
        scanWindow: Int = 500,
    ): Int {
        if (messageIds.isEmpty()) return 0
        val store = session.store ?: return 0
        if (!session.reconnectStore()) return 0
        val normalized = messageIds.map { EmailThreading.normalizeMessageId(it) }.toSet()
        val src = store.getFolder(sourceFolder)
        val dest = store.getFolder(destFolder)
        if (!src.exists() || !dest.exists()) return 0
        src.open(Folder.READ_WRITE)
        return try {
            val count = src.messageCount
            if (count <= 0) return 0
            val start = (count - scanWindow + 1).coerceAtLeast(1)
            val messages = src.getMessages(start, count)
            val matched = mutableListOf<Message>()
            for (mail in messages) {
                if (mail == null) continue
                val mime = mail as? MimeMessage ?: continue
                val mid = EmailThreading.normalizeMessageId(
                    mime.messageID?.takeIf { it.isNotBlank() } ?: continue,
                )
                if (mid in normalized) matched += mail
            }
            if (matched.isEmpty()) return 0
            src.copyMessages(matched.toTypedArray(), dest)
            for (mail in matched) {
                mail.setFlag(Flags.Flag.DELETED, true)
            }
            matched.size
        } catch (e: Exception) {
            Timber.w(e, "IMAP move failed %s → %s", sourceFolder, destFolder)
            0
        } finally {
            runCatching { src.close(expunge) }
        }
    }

    fun setFlagsByMessageIds(
        session: EmailSession,
        folderName: String,
        messageIds: Collection<String>,
        flag: Flags.Flag,
        value: Boolean,
        scanWindow: Int = 500,
    ): Int {
        if (messageIds.isEmpty()) return 0
        val store = session.store ?: return 0
        if (!session.reconnectStore()) return 0
        val normalized = messageIds.map { EmailThreading.normalizeMessageId(it) }.toSet()
        val folder = store.getFolder(folderName)
        if (!folder.exists()) return 0
        folder.open(Folder.READ_WRITE)
        return try {
            val count = folder.messageCount
            if (count <= 0) return 0
            val start = (count - scanWindow + 1).coerceAtLeast(1)
            var marked = 0
            for (mail in folder.getMessages(start, count)) {
                if (mail == null) continue
                val mime = mail as? MimeMessage ?: continue
                val mid = EmailThreading.normalizeMessageId(
                    mime.messageID?.takeIf { it.isNotBlank() } ?: continue,
                )
                if (mid !in normalized) continue
                mime.setFlag(flag, value)
                marked++
            }
            marked
        } catch (e: Exception) {
            Timber.w(e, "IMAP STORE flags failed in %s", folderName)
            0
        } finally {
            runCatching { folder.close(false) }
        }
    }

    fun search(
        session: EmailSession,
        folderName: String,
        query: String,
        limit: Int = 50,
    ): List<SearchHit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val store = session.store ?: return emptyList()
        if (!session.reconnectStore()) return emptyList()
        val folder = store.getFolder(folderName)
        if (!folder.exists()) return emptyList()
        folder.open(Folder.READ_ONLY)
        return try {
            val term: SearchTerm = OrTerm(
                arrayOf(
                    SubjectTerm(q),
                    FromStringTerm(q),
                    BodyTerm(q),
                ),
            )
            val found = folder.search(term) ?: emptyArray()
            val uidFolder = folder as? UIDFolder
            found.takeLast(limit).mapNotNull { mail ->
                val mime = mail as? MimeMessage ?: return@mapNotNull null
                SearchHit(
                    uid = uidFolder?.getUID(mail),
                    messageId = mime.messageID?.let { EmailThreading.normalizeMessageId(it) },
                    subject = mime.subject,
                    from = mime.from?.firstOrNull()?.toString(),
                )
            }
        } catch (e: Exception) {
            Timber.w(e, "IMAP SEARCH failed in %s", folderName)
            emptyList()
        } finally {
            runCatching { folder.close(false) }
        }
    }

    /**
     * Fetches up to [chunkSize] messages with UID strictly less than [beforeUidExclusive]
     * (or the oldest [chunkSize] when [beforeUidExclusive] is null/0 and no prior watermark).
     */
    fun fetchOlderChunk(
        session: EmailSession,
        folderName: String,
        beforeUidExclusive: Long,
        chunkSize: Int = 50,
    ): Pair<List<MimeMessage>, Long?> {
        val store = session.store ?: return emptyList<MimeMessage>() to null
        if (!session.reconnectStore()) return emptyList<MimeMessage>() to null
        val folder = store.getFolder(folderName)
        if (!folder.exists()) return emptyList<MimeMessage>() to null
        folder.open(Folder.READ_ONLY)
        return try {
            val uidFolder = folder as? UIDFolder
            if (uidFolder == null) {
                // Sequence fallback: skip newest 100, take previous chunk.
                val count = folder.messageCount
                if (count <= 100) return emptyList<MimeMessage>() to null
                val end = (count - 100).coerceAtLeast(1)
                val start = (end - chunkSize + 1).coerceAtLeast(1)
                if (start > end) return emptyList<MimeMessage>() to null
                val msgs = folder.getMessages(start, end).filterNotNull().mapNotNull { it as? MimeMessage }
                msgs to null
            } else {
                val endUid = when {
                    beforeUidExclusive > 1L -> beforeUidExclusive - 1
                    else -> {
                        val count = folder.messageCount
                        if (count <= 0) return emptyList<MimeMessage>() to null
                        uidFolder.getUID(folder.getMessage(count))
                    }
                }
                if (endUid <= 0L) return emptyList<MimeMessage>() to null
                // Fetch a window of UIDs ending at endUid.
                val startUid = (endUid - chunkSize + 1).coerceAtLeast(1L)
                val msgs = uidFolder.getMessagesByUID(startUid, endUid)
                    ?.filterNotNull()
                    ?.mapNotNull { it as? MimeMessage }
                    .orEmpty()
                val minUid = msgs.minOfOrNull { uidFolder.getUID(it) }
                msgs to minUid
            }
        } catch (e: Exception) {
            Timber.w(e, "IMAP older backfill failed in %s", folderName)
            emptyList<MimeMessage>() to null
        } finally {
            runCatching { folder.close(false) }
        }
    }
}
