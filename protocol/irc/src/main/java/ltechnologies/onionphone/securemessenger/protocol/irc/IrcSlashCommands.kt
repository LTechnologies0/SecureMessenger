package ltechnologies.onionphone.securemessenger.protocol.irc

import ltechnologies.onionphone.securemessenger.core.model.MessageKind
import org.kitteh.irc.client.library.Client
import org.kitteh.irc.client.library.element.mode.ChannelMode
import org.kitteh.irc.client.library.element.mode.ChannelUserMode
import org.kitteh.irc.client.library.element.mode.ModeStatus

/**
 * IRC client slash commands executed against a Kitteh [Client].
 * Safe CTCP only (no DCC). Channel ops use MODE / Kitteh commands.
 */
internal object IrcSlashCommands {

    data class Outcome(
        val displayBody: String,
        val kind: MessageKind = MessageKind.SYSTEM,
        /** When true, caller should not locally mirror an outgoing PRIVMSG (echo-message). */
        val awaitEcho: Boolean = false,
    )

    fun tryHandle(
        client: Client,
        conversationRemote: String,
        effectiveNick: String,
        negotiatedCaps: Set<String>,
        text: String,
    ): Outcome? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("/")) return null
        val parts = trimmed.split(Regex("\\s+"), limit = 3)
        val cmd = parts[0].lowercase()
        val arg1 = parts.getOrNull(1).orEmpty()
        val rest = when {
            parts.size >= 3 -> parts[2]
            else -> ""
        }
        val channelish = IrcTargets.isChannel(conversationRemote)
        val channel = if (channelish) conversationRemote else null

        return when (cmd) {
            "/help" -> Outcome(helpText())
            "/caps" -> Outcome(
                if (negotiatedCaps.isEmpty()) {
                    "IRCv3: (aucune capa négociée encore)"
                } else {
                    "IRCv3: ${negotiatedCaps.sorted().joinToString(" ")}"
                },
            )
            "/me" -> {
                val action = trimmed.removePrefix(parts[0]).trim()
                if (action.isEmpty()) return Outcome("Usage: /me action")
                client.sendCtcpMessage(conversationRemote, "ACTION $action")
                Outcome("* $effectiveNick $action", MessageKind.TEXT, awaitEcho = false)
            }
            "/topic" -> {
                val ch = requireChannel(channel, arg1.takeIf { IrcTargets.isChannel(it) })
                    ?: return Outcome("Usage: /topic [#chan] [texte]")
                val topicText = when {
                    IrcTargets.isChannel(arg1) -> rest
                    else -> listOf(arg1, rest).filter { it.isNotBlank() }.joinToString(" ").trim()
                }
                val target = client.getChannel(ch).orElse(null)
                    ?: return Outcome("Canal inconnu: $ch")
                if (topicText.isBlank()) {
                    target.commands().topic().query().execute()
                    val current = target.topic.value.orElse("(vide)")
                    Outcome("Topic $ch: $current")
                } else {
                    target.commands().topic().topic(topicText).execute()
                    Outcome("Topic $ch → $topicText")
                }
            }
            "/whois" -> {
                val nick = arg1.ifBlank { if (!channelish) conversationRemote else "" }
                if (nick.isBlank() || IrcTargets.isChannel(nick)) {
                    return Outcome("Usage: /whois <nick>")
                }
                client.commands().whois().target(IrcTargets.normalizeNick(nick)).execute()
                Outcome("WHOIS $nick…")
            }
            "/nick" -> {
                if (arg1.isBlank()) return Outcome("Usage: /nick <nouveau>")
                client.setNick(arg1)
                Outcome("NICK → $arg1")
            }
            "/join" -> {
                if (arg1.isBlank()) return Outcome("Usage: /join <#chan> [key]")
                val name = IrcTargets.canonicalChannel(arg1)
                val key = rest.trim().takeIf { it.isNotEmpty() }
                if (key != null) {
                    client.addKeyProtectedChannel(name, key)
                } else {
                    client.addChannel(name)
                }
                Outcome("JOIN $name")
            }
            "/part", "/leave" -> {
                val ch = when {
                    IrcTargets.isChannel(arg1) -> IrcTargets.canonicalChannel(arg1)
                    channel != null -> channel
                    else -> return Outcome("Usage: /part [#chan] [raison]")
                }
                val reason = when {
                    IrcTargets.isChannel(arg1) -> rest
                    else -> listOf(arg1, rest).filter { it.isNotBlank() }.joinToString(" ")
                }.ifBlank { "SecureMessenger" }
                client.removeChannel(ch, reason)
                Outcome("PART $ch ($reason)")
            }
            "/kick" -> {
                val ch = channel ?: return Outcome("/kick uniquement dans un canal")
                if (arg1.isBlank()) return Outcome("Usage: /kick <nick> [raison]")
                val target = client.getChannel(ch).orElse(null)
                    ?: return Outcome("Canal inconnu: $ch")
                val kickCmd = target.commands().kick().target(IrcTargets.normalizeNick(arg1))
                if (rest.isNotBlank()) kickCmd.reason(rest)
                kickCmd.execute()
                Outcome("KICK $arg1 de $ch")
            }
            "/invite" -> {
                val ch = when {
                    IrcTargets.isChannel(rest) -> IrcTargets.canonicalChannel(rest)
                    channel != null -> channel
                    else -> return Outcome("Usage: /invite <nick> [#chan]")
                }
                if (arg1.isBlank()) return Outcome("Usage: /invite <nick> [#chan]")
                client.sendRawLine("INVITE ${IrcTargets.normalizeNick(arg1)} $ch")
                Outcome("INVITE $arg1 → $ch")
            }
            "/mode" -> {
                val (ch, modeSpec) = when {
                    IrcTargets.isChannel(arg1) ->
                        IrcTargets.canonicalChannel(arg1) to rest
                    channel != null ->
                        channel to listOf(arg1, rest).filter { it.isNotBlank() }.joinToString(" ")
                    else -> return Outcome("Usage: /mode [#chan] <modes> [params]")
                }
                if (modeSpec.isBlank()) {
                    val modes = client.getChannel(ch).orElse(null)?.modes?.asString ?: "?"
                    return Outcome("MODE $ch: $modes")
                }
                client.sendRawLine("MODE $ch $modeSpec")
                Outcome("MODE $ch $modeSpec")
            }
            "/op" -> modeUser(client, channel, arg1, 'o', add = true)
            "/deop" -> modeUser(client, channel, arg1, 'o', add = false)
            "/voice" -> modeUser(client, channel, arg1, 'v', add = true)
            "/devoice" -> modeUser(client, channel, arg1, 'v', add = false)
            "/ban" -> {
                val ch = channel ?: return Outcome("/ban uniquement dans un canal")
                if (arg1.isBlank()) return Outcome("Usage: /ban <nick|mask>")
                val mask = if (arg1.contains("!") || arg1.contains("@")) arg1 else "$arg1!*@*"
                applyMaskMode(client, ch, 'b', mask, add = true)
            }
            "/unban" -> {
                val ch = channel ?: return Outcome("/unban uniquement dans un canal")
                if (arg1.isBlank()) return Outcome("Usage: /unban <mask>")
                applyMaskMode(client, ch, 'b', arg1, add = false)
            }
            "/banlist" -> {
                val ch = when {
                    IrcTargets.isChannel(arg1) -> IrcTargets.canonicalChannel(arg1)
                    channel != null -> channel
                    else -> return Outcome("Usage: /banlist [#chan]")
                }
                val chan = client.getChannel(ch).orElse(null)
                val banMode = ChannelMode.get(client, 'b').orElse(null)
                if (chan != null && banMode != null) {
                    chan.setModeInfoTracking(banMode, true)
                }
                client.sendRawLine("MODE $ch b")
                Outcome("Banlist $ch…")
            }
            "/key" -> {
                val ch = channel ?: return Outcome("/key uniquement dans un canal")
                when {
                    arg1.equals("-", ignoreCase = true) || arg1.equals("off", ignoreCase = true) -> {
                        client.sendRawLine("MODE $ch -k")
                        Outcome("MODE $ch -k")
                    }
                    arg1.isBlank() -> Outcome("Usage: /key <mot> | /key -")
                    else -> {
                        client.sendRawLine("MODE $ch +k $arg1")
                        Outcome("MODE $ch +k ***")
                    }
                }
            }
            "/notice" -> {
                if (arg1.isBlank() || rest.isBlank()) return Outcome("Usage: /notice <cible> <texte>")
                client.sendNotice(arg1, rest)
                Outcome("NOTICE → $arg1: $rest", MessageKind.SYSTEM)
            }
            "/msg", "/query" -> {
                if (arg1.isBlank() || rest.isBlank()) return Outcome("Usage: /msg <nick> <texte>")
                client.sendMultiLineMessage(IrcTargets.normalizeNick(arg1), rest)
                Outcome("→ $arg1: $rest", MessageKind.TEXT)
            }
            "/quote", "/raw" -> {
                val raw = trimmed.removePrefix(parts[0]).trim()
                if (raw.isBlank()) return Outcome("Usage: /quote <ligne IRC>")
                if (raw.uppercase().startsWith("DCC") || raw.contains("\u0001DCC", ignoreCase = true)) {
                    return Outcome("DCC bloqué (Tor-unsafe)")
                }
                client.sendRawLine(raw)
                Outcome("RAW: $raw")
            }
            "/names" -> {
                val ch = when {
                    IrcTargets.isChannel(arg1) -> IrcTargets.canonicalChannel(arg1)
                    channel != null -> channel
                    else -> return Outcome("Usage: /names [#chan]")
                }
                val nicks = client.getChannel(ch).orElse(null)?.nicknames?.sorted().orEmpty()
                Outcome(
                    if (nicks.isEmpty()) {
                        "NAMES $ch: (vide / hors canal)"
                    } else {
                        "NAMES $ch (${nicks.size}): ${nicks.joinToString(" ")}"
                    },
                )
            }
            "/away" -> {
                val msg = listOf(arg1, rest).filter { it.isNotBlank() }.joinToString(" ")
                if (msg.isBlank()) {
                    client.commands().away().away("Away").execute()
                    Outcome("AWAY")
                } else {
                    client.commands().away().away(msg).execute()
                    Outcome("AWAY: $msg")
                }
            }
            "/back", "/unaway" -> {
                client.commands().away().notAway().execute()
                Outcome("Back (AWAY cleared)")
            }
            "/ctcp" -> {
                if (arg1.isBlank() || rest.isBlank()) {
                    return Outcome("Usage: /ctcp <nick> VERSION|PING|TIME|CLIENTINFO [payload]")
                }
                val ctcpParts = rest.split(Regex("\\s+"), limit = 2)
                val type = ctcpParts[0].uppercase()
                if (type !in SAFE_CTCP) {
                    return Outcome("CTCP $type refusé (autorisés: ${SAFE_CTCP.joinToString()})")
                }
                val payload = ctcpParts.getOrNull(1)?.let { "$type $it" } ?: type
                client.sendCtcpMessage(IrcTargets.normalizeNick(arg1), payload)
                Outcome("CTCP $type → $arg1")
            }
            "/react" -> {
                if (arg1.isBlank() || rest.isBlank()) {
                    return Outcome("Usage: /react <msgid> <emoji>")
                }
                if (!negotiatedCaps.contains("message-tags")) {
                    return Outcome("Serveur sans message-tags — react indisponible")
                }
                sendReact(client, conversationRemote, arg1, rest.trim(), unreact = false)
                Outcome("React $rest → msgid $arg1", MessageKind.SYSTEM)
            }
            "/unreact" -> {
                if (arg1.isBlank() || rest.isBlank()) {
                    return Outcome("Usage: /unreact <msgid> <emoji>")
                }
                if (!negotiatedCaps.contains("message-tags")) {
                    return Outcome("Serveur sans message-tags — unreact indisponible")
                }
                sendReact(client, conversationRemote, arg1, rest.trim(), unreact = true)
                Outcome("Unreact $rest → msgid $arg1", MessageKind.SYSTEM)
            }
            else -> Outcome("Commande inconnue: $cmd — /help")
        }
    }

    private val SAFE_CTCP = setOf("VERSION", "PING", "TIME", "CLIENTINFO")

    private fun helpText(): String = buildString {
        appendLine("Commandes IRC:")
        append(
            "/me /topic /whois /nick /join /part /kick /invite /mode /op /deop /voice /devoice ",
        )
        append(
            "/ban /unban /banlist /key /notice /msg /names /away /back /ctcp /react /unreact /caps /quote /help",
        )
    }.trimEnd()

    private fun requireChannel(current: String?, maybe: String?): String? =
        when {
            maybe != null -> IrcTargets.canonicalChannel(maybe)
            current != null -> current
            else -> null
        }

    private fun modeUser(
        client: Client,
        channel: String?,
        nick: String,
        modeChar: Char,
        add: Boolean,
    ): Outcome {
        if (channel == null) return Outcome("Commande canal uniquement")
        if (nick.isBlank()) return Outcome("Usage: nick requis")
        val chan = client.getChannel(channel).orElse(null)
            ?: return Outcome("Canal inconnu: $channel")
        val userMode = ChannelUserMode.get(client, modeChar).orElse(null)
        val user = chan.getUser(IrcTargets.normalizeNick(nick)).orElse(null)
        if (userMode != null && user != null) {
            val action = if (add) ModeStatus.Action.ADD else ModeStatus.Action.REMOVE
            chan.commands().mode().add(action, userMode, user).execute()
        } else {
            val sign = if (add) "+" else "-"
            client.sendRawLine("MODE $channel $sign$modeChar ${IrcTargets.normalizeNick(nick)}")
        }
        val label = if (add) "+$modeChar" else "-$modeChar"
        return Outcome("MODE $channel $label $nick")
    }

    private fun applyMaskMode(
        client: Client,
        channel: String,
        modeChar: Char,
        mask: String,
        add: Boolean,
    ): Outcome {
        val chan = client.getChannel(channel).orElse(null)
        val mode = ChannelMode.get(client, modeChar).orElse(null)
        if (chan != null && mode != null) {
            val action = if (add) ModeStatus.Action.ADD else ModeStatus.Action.REMOVE
            chan.commands().mode().add(action, mode, mask).execute()
        } else {
            val sign = if (add) "+" else "-"
            client.sendRawLine("MODE $channel $sign$modeChar $mask")
        }
        val sign = if (add) "+" else "-"
        return Outcome("MODE $channel $sign$modeChar $mask")
    }

    private fun sendReact(
        client: Client,
        target: String,
        msgid: String,
        emoji: String,
        unreact: Boolean,
    ) {
        val tag = if (unreact) "+draft/unreact" else "+draft/react"
        val line = "@+reply=${escapeTag(msgid)};$tag=${escapeTag(emoji)} TAGMSG $target"
        client.sendRawLine(line)
    }

    /** IRCv3 message-tag value escaping. */
    fun escapeTag(value: String): String = buildString(value.length) {
        for (c in value) {
            when (c) {
                ';' -> append("\\:")
                ' ' -> append("\\s")
                '\\' -> append("\\\\")
                '\r' -> append("\\r")
                '\n' -> append("\\n")
                else -> append(c)
            }
        }
    }

    fun unescapeTag(value: String): String = buildString(value.length) {
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                when (value[i + 1]) {
                    ':' -> append(';')
                    's' -> append(' ')
                    '\\' -> append('\\')
                    'r' -> append('\r')
                    'n' -> append('\n')
                    else -> append(value[i + 1])
                }
                i += 2
            } else {
                append(c)
                i++
            }
        }
    }
}
