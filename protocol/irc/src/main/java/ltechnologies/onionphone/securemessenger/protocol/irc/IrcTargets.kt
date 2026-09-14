package ltechnologies.onionphone.securemessenger.protocol.irc

import ltechnologies.onionphone.securemessenger.core.model.ConversationIds

/** IRC target helpers shared by the Kitteh adapter and unit tests. */
object IrcTargets {
    fun isChannel(target: String): Boolean {
        val t = target.trim()
        return t.startsWith('#') || t.startsWith('&') || t.startsWith('!') || t.startsWith('+')
    }

    fun normalizeNick(nick: String): String = nick.trim().removePrefix("@").removePrefix("+")

    fun conversationId(accountId: String, remoteId: String): String =
        ConversationIds.encode(accountId, remoteId)

    fun accountIdFromConversation(conversationId: String): String =
        ConversationIds.accountId(conversationId) ?: conversationId

    fun remoteFromConversation(conversationId: String): String =
        ConversationIds.remoteId(conversationId) ?: conversationId

    fun parseChannels(raw: String?): List<String> =
        raw.orEmpty()
            .split(',', ' ', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { if (isChannel(it)) it else "#$it" }
            .distinct()
}
