package ltechnologies.onionphone.securemessenger.core.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import ltechnologies.onionphone.securemessenger.core.model.SanitizedText

/**
 * Keystore-backed encrypted storage for every account's secrets (passwords, tokens,
 * homeserver URLs) and small metadata (protocol, display name).
 *
 * Backed by [EncryptedSharedPreferences] with an [MasterKey] AES-256-GCM master key.
 * Every value is namespaced by `accountId` so multiple accounts of the same or
 * different protocols never collide (`"<accountId>:<key>"`).
 */
@Singleton
class EncryptedCredentialStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    /** Stores an arbitrary secret [value] under [key], namespaced to [accountId]. */
    fun put(accountId: String, key: String, value: String) {
        prefs.edit().putString(prefKey(accountId, key), value).apply()
    }

    /** Records the [protocol] id and human-readable [displayName] for [accountId]. */
    fun putAccountMeta(accountId: String, protocol: String, displayName: String) {
        prefs.edit()
            .putString(prefKey(accountId, META_PROTOCOL), protocol)
            .putString(prefKey(accountId, META_DISPLAY_NAME), displayName)
            .apply()
    }

    /** Returns the stored protocol id for [accountId], or `null` if unknown. */
    fun getProtocol(accountId: String): String? =
        prefs.getString(prefKey(accountId, META_PROTOCOL), null)

    /** Returns the stored display name for [accountId], or `null` if unknown. */
    fun getDisplayName(accountId: String): String? =
        prefs.getString(prefKey(accountId, META_DISPLAY_NAME), null)

    /**
     * Returns every account id present in the store — derived from the namespace
     * prefix of each stored key, filtered to only ids that have a recorded protocol.
     */
    fun listAccountIds(): Set<String> =
        prefs.all.keys
            .mapNotNull { key ->
                val colon = key.indexOf(':')
                if (colon <= 0) null else key.substring(0, colon)
            }
            .filter { id -> getProtocol(id) != null }
            .toSet()

    /** Returns the secret stored under [key] for [accountId], or `null` if absent. */
    fun get(accountId: String, key: String): String? =
        prefs.getString(prefKey(accountId, key), null)

    /** Deletes every stored key (secrets + metadata) belonging to [accountId]. */
    fun removeAccount(accountId: String) {
        val prefix = "$accountId:"
        prefs.edit().apply {
            prefs.all.keys.filter { it.startsWith(prefix) }.forEach { remove(it) }
        }.apply()
    }

    /**
     * Returns every non-metadata key/value pair stored for [accountId], with the
     * account-id namespace prefix stripped from the keys.
     */
    fun getAllForAccount(accountId: String): Map<String, String> {
        val prefix = "$accountId:"
        return prefs.all
            .filterKeys { it.startsWith(prefix) }
            .mapKeys { it.key.removePrefix(prefix) }
            .mapValues { it.value.toString() }
            .filterKeys { !it.startsWith("__") }
    }

    private fun prefKey(accountId: String, key: String) = "$accountId:$key"

    companion object {
        private const val PREFS_NAME = "secure_messenger_credentials"
        const val META_PROTOCOL = "__protocol"
        const val META_DISPLAY_NAME = "__displayName"
        private const val PROXY_PASSWORD_KEY = "__proxy:password"
    }

    /** Stores (or clears, if [password] is null/blank) the global Tor SOCKS5 proxy password. */
    fun putProxyPassword(password: String?) {
        prefs.edit().apply {
            if (password.isNullOrBlank()) {
                remove(PROXY_PASSWORD_KEY)
            } else {
                putString(PROXY_PASSWORD_KEY, password)
            }
        }.apply()
    }

    /** Returns the global Tor SOCKS5 proxy password, or `null` if not configured. */
    fun getProxyPassword(): String? = prefs.getString(PROXY_PASSWORD_KEY, null)
}

/**
 * Strips unsafe HTML/script content from incoming message text before it is
 * rendered, and flags links using dangerous URI schemes.
 */
object MessageSanitizer {
    private val HTML_TAG = Regex("<[^>]+>")
    private val DANGEROUS_URI = Regex("(?i)(javascript:|data:|vbscript:)")

    /** Removes HTML tags and truncates [raw] to [MAX_LENGTH], returning a [SanitizedText]. */
    fun sanitize(raw: String): SanitizedText {
        val stripped = raw
            .replace(HTML_TAG, "")
            .trim()
            .take(MAX_LENGTH)
        return SanitizedText(stripped)
    }

    /** Returns `false` if [url] uses a `javascript:`, `data:`, or `vbscript:` scheme. */
    fun isSafeLink(url: String): Boolean = !DANGEROUS_URI.containsMatchIn(url)

    private const val MAX_LENGTH = 32_768
}

/** Redacts tokens, passwords, and phone numbers from log strings before they are written. */
object LogRedactor {
    private val TOKEN = Regex("(?i)(token|password|secret|bearer)\\s*[=:]\\s*\\S+")
    private val PHONE = Regex("\\+?\\d{10,15}")

    /** Returns [message] with any token/password/secret/bearer values and phone numbers masked. */
    fun redact(message: String): String =
        message
            .replace(TOKEN, "$1=[REDACTED]")
            .replace(PHONE, "[PHONE]")
}
