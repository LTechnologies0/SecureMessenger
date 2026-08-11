package ltechnologies.onionphone.securemessenger.protocol.email

import java.util.Locale
import java.util.regex.Pattern

/**
 * RFC 5321/5322-inspired mailbox validation (strict allowlist, not full ABNF).
 */
object EmailAddress {
    private val LOCAL = Pattern.compile("^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]{1,64}$")
    private val DOMAIN_LABEL = Pattern.compile("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$")

    fun normalize(raw: String): String = raw.trim().lowercase(Locale.ROOT)

    fun isValid(raw: String): Boolean {
        val value = normalize(raw)
        if (value.length > 254) return false
        val at = value.lastIndexOf('@')
        if (at <= 0 || at == value.lastIndex) return false
        val local = value.substring(0, at)
        val domain = value.substring(at + 1)
        if (local.startsWith('.') || local.endsWith('.') || local.contains("..")) return false
        if (!LOCAL.matcher(local).matches()) return false
        val labels = domain.split('.')
        if (labels.size < 2) return false
        return labels.all { DOMAIN_LABEL.matcher(it).matches() }
    }

    fun requireValid(raw: String): String {
        val normalized = normalize(raw)
        require(isValid(normalized)) { "Invalid email address" }
        return normalized
    }

    /** Extracts bare address from `Display Name <user@domain>` or returns trimmed input. */
    fun extract(raw: String): String {
        val trimmed = raw.trim()
        val start = trimmed.lastIndexOf('<')
        val end = trimmed.lastIndexOf('>')
        if (start >= 0 && end > start) {
            return normalize(trimmed.substring(start + 1, end))
        }
        return normalize(trimmed)
    }
}
