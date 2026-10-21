package ltechnologies.onionphone.securemessenger.protocol.email

import jakarta.mail.Store
import jakarta.mail.Folder

/** Common IMAP Sent folder names (Thunderbird / K-9 / Gmail conventions). */
object ImapFolderNames {
    val SENT_CANDIDATES = listOf(
        "Sent",
        "Sent Items",
        "Sent Messages",
        "[Gmail]/Sent Mail",
        "INBOX.Sent",
        "INBOX/Sent",
    )

    fun detectSentFolder(store: Store): String? {
        for (name in SENT_CANDIDATES) {
            val folder: Folder = store.getFolder(name)
            if (folder.exists()) return name
        }
        return null
    }
}
