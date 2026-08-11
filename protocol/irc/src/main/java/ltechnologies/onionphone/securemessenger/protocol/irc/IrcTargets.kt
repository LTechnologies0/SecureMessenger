package ltechnologies.onionphone.securemessenger.protocol.irc

/** IRC target helpers shared by the Kitteh adapter and unit tests. */
object IrcTargets {
    fun isChannel(target: String): Boolean {
        val t = target.trim()
        return t.startsWith('#') || t.startsWith('&') || t.startsWith('!') || t.startsWith('+')
    }

    fun normalizeNick(nick: String): String = nick.trim().removePrefix("@").removePrefix("+")

    fun conversationId(accountId: String, remoteId: String): String = "${accountId}_$remoteId"

    fun accountIdFromConversation(conversationId: String): String =
        conversationId.substringBefore('_', missingDelimiterValue = conversationId)

    fun remoteFromConversation(conversationId: String): String =
        conversationId.substringAfter('_', missingDelimiterValue = conversationId)

    fun parseChannels(raw: String?): List<String> =
        raw.orEmpty()
            .split(',', ' ', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { if (isChannel(it)) it else "#$it" }
            .distinct()
}
