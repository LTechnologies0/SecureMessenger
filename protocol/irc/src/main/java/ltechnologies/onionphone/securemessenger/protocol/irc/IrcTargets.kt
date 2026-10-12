package ltechnologies.onionphone.securemessenger.protocol.irc

import java.util.Locale
import ltechnologies.onionphone.securemessenger.core.model.ConversationIds

/** IRC target helpers shared by the Kitteh adapter and unit tests. */
object IrcTargets {
    fun isChannel(target: String): Boolean {
        val t = target.trim()
        return t.startsWith('#') || t.startsWith('&') || t.startsWith('!') || t.startsWith('+')
    }

    fun normalizeNick(nick: String): String = nick.trim().removePrefix("@").removePrefix("+")

    /** RFC 1459 case-folding for nick comparisons. */
    fun foldNick(nick: String): String = normalizeNick(nick).lowercase(Locale.US)

    /** Case-fold channel names while preserving type prefix (`#`, `&`, …). */
    fun foldChannel(channel: String): String {
        val t = channel.trim()
        if (!isChannel(t)) return foldNick(t)
        return t.first() + t.drop(1).lowercase(Locale.US)
    }

    fun canonicalRemote(remote: String): String =
        if (isChannel(remote)) foldChannel(remote) else foldNick(remote)

    fun canonicalChannel(channel: String): String {
        val t = channel.trim()
        val withPrefix = if (isChannel(t)) t else "#$t"
        return foldChannel(withPrefix)
    }

    fun sameNick(a: String, b: String): Boolean = foldNick(a) == foldNick(b)

    fun sameChannel(a: String, b: String): Boolean = foldChannel(a) == foldChannel(b)

    fun conversationId(accountId: String, remoteId: String): String =
        ConversationIds.encode(accountId, canonicalRemote(remoteId))

    fun accountIdFromConversation(conversationId: String): String =
        ConversationIds.accountId(conversationId) ?: conversationId

    fun remoteFromConversation(conversationId: String): String =
        ConversationIds.remoteId(conversationId) ?: conversationId

    fun parseChannels(raw: String?): List<String> =
        raw.orEmpty()
            .split(',', ' ', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { canonicalChannel(it) }
            .distinctBy { foldChannel(it) }
}
