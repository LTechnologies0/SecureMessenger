package ltechnologies.onionphone.securemessenger.protocol.irc

import ltechnologies.onionphone.securemessenger.core.model.ConversationIds
import org.kitteh.irc.client.library.feature.CaseMapping

/** IRC target helpers shared by the Kitteh adapter and unit tests. */
object IrcTargets {
    /**
     * Kitteh [org.kitteh.irc.client.library.defaults.feature.DefaultServerInfo] channel pattern:
     * `([#!&+][^ ,\u0007\r\n]+)`. RFC 2812 §1.3 prefixes are `#`, `&`, `+`, `!`.
     */
    private val channelPattern = Regex("([#!&+][^ ,\u0007\r\n]+)")

    fun isChannel(target: String): Boolean = channelPattern.matches(target.trim())

    fun normalizeNick(nick: String): String = nick.trim().removePrefix("@").removePrefix("+")

    /**
     * RFC 2812 §2.2 / Kitteh [CaseMapping.RFC1459] (ServerInfo default until CASEMAPPING):
     * `A-Z` and `[]\^` fold to `a-z` and `{}|~`.
     */
    fun foldNick(nick: String, mapping: CaseMapping = CaseMapping.RFC1459): String =
        mapping.toLowerCase(normalizeNick(nick))

    /** Same fold as [foldNick]; channel type prefixes sit outside the RFC1459 nick range. */
    fun foldChannel(channel: String, mapping: CaseMapping = CaseMapping.RFC1459): String {
        val t = channel.trim()
        if (!isChannel(t)) return foldNick(t, mapping)
        return mapping.toLowerCase(t)
    }

    fun canonicalRemote(remote: String, mapping: CaseMapping = CaseMapping.RFC1459): String =
        if (isChannel(remote)) foldChannel(remote, mapping) else foldNick(remote, mapping)

    fun canonicalChannel(channel: String, mapping: CaseMapping = CaseMapping.RFC1459): String {
        val t = channel.trim()
        val withPrefix = if (isChannel(t)) t else "#$t"
        return foldChannel(withPrefix, mapping)
    }

    fun sameNick(a: String, b: String, mapping: CaseMapping = CaseMapping.RFC1459): Boolean =
        foldNick(a, mapping) == foldNick(b, mapping)

    fun sameChannel(a: String, b: String, mapping: CaseMapping = CaseMapping.RFC1459): Boolean =
        foldChannel(a, mapping) == foldChannel(b, mapping)

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
