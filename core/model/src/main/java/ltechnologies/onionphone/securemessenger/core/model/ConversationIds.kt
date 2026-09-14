package ltechnologies.onionphone.securemessenger.core.model

/**
 * Shared conversation-id codec for underscore-separated IM protocols
 * (`${accountId}_$remoteId`) and colon-separated email threads
 * (`$accountId:mailbox:` / `$accountId:thread:`).
 *
 * Account ids are UUIDs (no `_`), so the **first** underscore separates account from remote.
 * When [accountId] is already known, prefer [remoteId] with that account (prefix strip) so
 * remotes that themselves contain `_` (e.g. Signal group ids) stay intact.
 */
object ConversationIds {

    fun encode(accountId: String, remoteId: String): String = "${accountId}_$remoteId"

    fun isEmailScheme(conversationId: String): Boolean =
        conversationId.contains(":mailbox:") ||
            conversationId.contains(":thread:") ||
            conversationId.contains(":msg:")

    fun accountId(conversationId: String): String? {
        if (isEmailScheme(conversationId)) {
            return conversationId.substringBefore(':').takeIf { it.isNotBlank() }
        }
        val sep = conversationId.indexOf('_')
        if (sep <= 0) return null
        return conversationId.substring(0, sep)
    }

    fun remoteId(conversationId: String): String? {
        if (isEmailScheme(conversationId)) return null
        val sep = conversationId.indexOf('_')
        if (sep < 0 || sep >= conversationId.lastIndex) return null
        return conversationId.substring(sep + 1)
    }

    /** Prefer when the owning account is known — safe if [remoteId] contains `_`. */
    fun remoteId(conversationId: String, accountId: String): String {
        val prefix = "${accountId}_"
        if (conversationId.startsWith(prefix)) return conversationId.removePrefix(prefix)
        return remoteId(conversationId).orEmpty()
    }

    fun emailAccountId(conversationId: String): String? =
        conversationId.substringBefore(':').takeIf { it.isNotBlank() && conversationId.contains(':') }
}
