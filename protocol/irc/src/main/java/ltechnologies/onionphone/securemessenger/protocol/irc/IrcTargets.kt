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
        parseChannelJoins(raw).map { it.name }

    /**
     * Auto-join entries. Supports `#chan`, `#chan=key`, `#chan:key`
     * (comma / semicolon / whitespace separated).
     */
    data class ChannelJoin(val name: String, val key: String? = null)

    fun parseChannelJoins(raw: String?): List<ChannelJoin> =
        raw.orEmpty()
            .split(',', ';')
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .flatMap { token ->
                // Allow space-separated bare channels inside a comma field: "#a #b"
                if (('=' !in token && ':' !in token) && ' ' in token) {
                    token.split(Regex("\\s+")).filter { it.isNotEmpty() }
                } else {
                    listOf(token)
                }
            }
            .map { token ->
                val sep = when {
                    '=' in token -> '='
                    // `#chan:key` — only split on first ':' after the channel name
                    token.indexOf(':') > 0 -> ':'
                    else -> null
                }
                if (sep == null) {
                    ChannelJoin(canonicalChannel(token), null)
                } else {
                    val idx = token.indexOf(sep)
                    val name = token.substring(0, idx).trim()
                    val key = token.substring(idx + 1).trim().takeIf { it.isNotEmpty() }
                    ChannelJoin(canonicalChannel(name), key)
                }
            }
            .distinctBy { foldChannel(it.name) }
            .toList()

    /** Merge `channels` + optional `channelKeys` (`#a=k1,#b=k2`) maps. */
    fun mergeChannelKeys(
        channelsRaw: String?,
        keysRaw: String?,
    ): List<ChannelJoin> {
        val base = parseChannelJoins(channelsRaw).toMutableList()
        val byName = base.associateBy { foldChannel(it.name) }.toMutableMap()
        parseChannelJoins(keysRaw).forEach { keyed ->
            if (keyed.key != null) {
                byName[foldChannel(keyed.name)] = keyed
            }
        }
        return byName.values.toList()
    }
}
