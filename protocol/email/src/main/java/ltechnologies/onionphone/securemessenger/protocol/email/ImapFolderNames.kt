package ltechnologies.onionphone.securemessenger.protocol.email

import jakarta.mail.Folder
import jakarta.mail.Store
import org.eclipse.angus.mail.imap.IMAPFolder

/** Common IMAP special-folder names (Thunderbird / K-9 / Gmail) + RFC 6154 roles. */
object ImapFolderNames {
    enum class Role {
        INBOX,
        SENT,
        DRAFTS,
        TRASH,
        ARCHIVE,
        JUNK,
        OTHER,
    }

    data class MailboxInfo(
        val fullName: String,
        val role: Role,
        val messageCount: Int = -1,
        val unreadCount: Int = -1,
    )

    val SENT_CANDIDATES = listOf(
        "Sent",
        "Sent Items",
        "Sent Messages",
        "[Gmail]/Sent Mail",
        "INBOX.Sent",
        "INBOX/Sent",
    )

    val DRAFTS_CANDIDATES = listOf(
        "Drafts",
        "Draft",
        "[Gmail]/Drafts",
        "INBOX.Drafts",
        "INBOX/Drafts",
    )

    val TRASH_CANDIDATES = listOf(
        "Trash",
        "Deleted",
        "Deleted Items",
        "Deleted Messages",
        "[Gmail]/Trash",
        "INBOX.Trash",
        "INBOX/Trash",
    )

    val ARCHIVE_CANDIDATES = listOf(
        "Archive",
        "Archives",
        "[Gmail]/All Mail",
        "INBOX.Archive",
        "INBOX/Archive",
    )

    val JUNK_CANDIDATES = listOf(
        "Junk",
        "Spam",
        "Bulk Mail",
        "[Gmail]/Spam",
        "INBOX.Junk",
        "INBOX/Spam",
    )

    fun detectSentFolder(store: Store): String? = detectByCandidates(store, SENT_CANDIDATES)

    fun detectDraftsFolder(store: Store): String? = detectByCandidates(store, DRAFTS_CANDIDATES)

    fun detectTrashFolder(store: Store): String? = detectByCandidates(store, TRASH_CANDIDATES)

    fun detectArchiveFolder(store: Store): String? = detectByCandidates(store, ARCHIVE_CANDIDATES)

    fun detectJunkFolder(store: Store): String? = detectByCandidates(store, JUNK_CANDIDATES)

    fun detectByCandidates(store: Store, candidates: List<String>): String? {
        for (name in candidates) {
            val folder: Folder = store.getFolder(name)
            if (folder.exists()) return name
        }
        return null
    }

    /**
     * Lists top-level + one-level children. Prefers RFC 6154 SPECIAL-USE attributes when Angus
     * exposes them; otherwise falls back to name heuristics.
     */
    fun listMailboxes(store: Store): List<MailboxInfo> {
        val root = store.defaultFolder
        val folders = root.list("*")
        return folders.map { folder ->
            val name = folder.fullName
            val role = roleFromAttributes(folder) ?: roleFromName(name)
            val counts = runCatching {
                if (folder.type and Folder.HOLDS_MESSAGES != 0) {
                    folder.messageCount to folder.unreadMessageCount
                } else {
                    -1 to -1
                }
            }.getOrDefault(-1 to -1)
            MailboxInfo(
                fullName = name,
                role = role,
                messageCount = counts.first,
                unreadCount = counts.second,
            )
        }.sortedBy { it.fullName.lowercase() }
    }

    fun roleFromName(fullName: String): Role {
        val n = fullName.lowercase()
        return when {
            n == "inbox" || n.endsWith("/inbox") || n.endsWith(".inbox") -> Role.INBOX
            SENT_CANDIDATES.any { fullName.equals(it, ignoreCase = true) } ||
                n.endsWith("/sent") || n.contains("sent mail") || n.endsWith(".sent") -> Role.SENT
            DRAFTS_CANDIDATES.any { fullName.equals(it, ignoreCase = true) } ||
                n.contains("draft") -> Role.DRAFTS
            TRASH_CANDIDATES.any { fullName.equals(it, ignoreCase = true) } ||
                n.contains("trash") || n.contains("deleted") -> Role.TRASH
            ARCHIVE_CANDIDATES.any { fullName.equals(it, ignoreCase = true) } ||
                n.contains("archive") || n.contains("all mail") -> Role.ARCHIVE
            JUNK_CANDIDATES.any { fullName.equals(it, ignoreCase = true) } ||
                n.contains("junk") || n.contains("spam") -> Role.JUNK
            else -> Role.OTHER
        }
    }

    private fun roleFromAttributes(folder: Folder): Role? {
        val imap = folder as? IMAPFolder ?: return null
        val attrs = runCatching { imap.attributes }.getOrNull() ?: return null
        for (attr in attrs) {
            when (attr.lowercase()) {
                "\\sent" -> return Role.SENT
                "\\drafts" -> return Role.DRAFTS
                "\\trash" -> return Role.TRASH
                "\\archive" -> return Role.ARCHIVE
                "\\junk" -> return Role.JUNK
                "\\inbox" -> return Role.INBOX
            }
        }
        return null
    }
}
