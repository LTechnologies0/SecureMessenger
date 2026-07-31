package ltechnologies.onionphone.securemessenger.core.model

enum class ProtocolId {
    XMPP,
    MATRIX,
    TELEGRAM,
    SIGNAL,
    EMAIL,
}

object FeatureFlags {
    val enabled: Set<ProtocolId> = setOf(
        ProtocolId.XMPP,
        ProtocolId.MATRIX,
        ProtocolId.TELEGRAM,
        ProtocolId.SIGNAL,
        ProtocolId.EMAIL,
    )
}

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR,
}

enum class MessageDirection {
    INCOMING,
    OUTGOING,
}

enum class DeliveryState {
    PENDING,
    SENT,
    DELIVERED,
    READ,
    FAILED,
}

data class AccountCredentials(
    val protocol: ProtocolId,
    val accountId: String,
    val displayName: String,
    val secrets: Map<String, String>,
)

enum class TorProvider {
    /**
     * OnionVPN PAC bridge (`http://127.0.0.1:18201/onionvpn.pac` → SOCKS5 `:18202`).
     * DNS via DNSCrypt, then Tor by IP (no Tor exit DNS).
     */
    ONIONVPN,
    /** Manual SOCKS5 endpoint (any Tor client). */
    CUSTOM,
    ;

    companion object {
        /** Maps legacy Orbot/InviZible prefs to OnionVPN. */
        fun fromStored(name: String?): TorProvider = when (name?.uppercase()) {
            "ORBOT", "INVIZIBLE" -> ONIONVPN
            "CUSTOM" -> CUSTOM
            "ONIONVPN" -> ONIONVPN
            else -> runCatching { valueOf(name ?: "") }.getOrDefault(ONIONVPN)
        }
    }
}

data class ProxyConfig(
    val host: String,
    val port: Int,
    val username: String? = null,
    val password: String? = null,
    /** When false (default), protocols use clearnet. Tor SOCKS is opt-in. */
    val torRequired: Boolean = false,
    val remoteDns: Boolean = true,
    val torProvider: TorProvider = TorProvider.ONIONVPN,
)

enum class AuthStepKind {
    NONE,
    TELEGRAM_SMS_CODE,
    TELEGRAM_PASSWORD,
    TELEGRAM_REGISTRATION,
    TELEGRAM_OTHER_DEVICE,
    MATRIX_SSO,
    SIGNAL_SMS_CODE,
    SIGNAL_CAPTCHA,
    SIGNAL_PIN,
    /** Secondary device link: [AuthStep.url] holds the `sgnl://linkdevice?...` QR payload. */
    SIGNAL_DEVICE_LINK,
}

data class AuthStep(
    val kind: AuthStepKind,
    val prompt: String,
    val fields: List<String> = emptyList(),
    /** Optional browser URL (e.g. Matrix SSO redirect). */
    val url: String? = null,
)

/** Deep-link target for Matrix SSO WebView redirects (`m.login.sso`). */
object MatrixSsoRedirect {
    const val SCHEME = "securemessenger"
    const val HOST = "matrix-sso"
    const val URI = "$SCHEME://$HOST"

    fun extractLoginToken(redirectUrl: String): String? {
        val q = redirectUrl.substringAfter('?', missingDelimiterValue = "")
        if (q.isEmpty()) return null
        return q.split('&').asSequence()
            .map { it.split('=', limit = 2) }
            .firstOrNull { it.size == 2 && (it[0] == "loginToken" || it[0] == "login_token") }
            ?.get(1)
            ?.takeIf { it.isNotBlank() }
            ?.let { java.net.URLDecoder.decode(it, Charsets.UTF_8.name()) }
    }

    fun matchesRedirect(url: String): Boolean =
        url.startsWith(URI) || url.startsWith("$URI?")
}

data class ProtocolCapabilities(
    val directMessages: Boolean = true,
    val groupChats: Boolean = false,
    val mediaSend: Boolean = false,
    val mediaReceive: Boolean = false,
    val typingIndicators: Boolean = false,
    val readReceipts: Boolean = false,
    val endToEndEncryption: Boolean = false,
    val requiresPhoneAuth: Boolean = false,
    /** Address-book style contacts (roster / GetContacts / CDS). */
    val contacts: Boolean = false,
    /** User can edit display name / bio / avatar via the app. */
    val profileEdit: Boolean = false,
    val voiceNotes: Boolean = false,
    val stickers: Boolean = false,
    val gifs: Boolean = false,
    val locationShare: Boolean = false,
    val polls: Boolean = false,
    val contactShare: Boolean = false,
    val ephemeralMessages: Boolean = false,
    /** Protocol can backfill older messages when opening a chat. */
    val messageHistory: Boolean = false,
    /** App-level JSON export of local conversations/messages. */
    val backupExport: Boolean = true,
)

enum class MessageKind {
    TEXT,
    IMAGE,
    VIDEO,
    FILE,
    GIF,
    STICKER,
    VOICE,
    LOCATION,
    POLL,
    CONTACT,
    SYSTEM,
    UNKNOWN,
}

data class Contact(
    val id: String,
    val protocol: ProtocolId,
    val accountId: String,
    val remoteId: String,
    val displayName: String,
    val handle: String? = null,
    val phone: String? = null,
    val avatarLocalPath: String? = null,
)

data class AccountProfile(
    val accountId: String,
    val protocol: ProtocolId,
    val displayName: String,
    val handle: String? = null,
    val phone: String? = null,
    val bio: String? = null,
    val avatarLocalPath: String? = null,
)

/**
 * Outgoing rich payloads beyond plain text / generic media.
 * Protocols map what they support; unsupported kinds return [SendResult.Failure].
 */
sealed class OutgoingContent {
    data class Text(val body: SanitizedText) : OutgoingContent()

    data class Media(
        val attachment: Attachment,
        val caption: SanitizedText? = null,
        val kind: MessageKind = MessageKind.FILE,
    ) : OutgoingContent()

    data class VoiceNote(
        val attachment: Attachment,
        val durationMs: Int = 0,
    ) : OutgoingContent()

    data class Location(
        val latitude: Double,
        val longitude: Double,
        val horizontalAccuracy: Double = 0.0,
        val livePeriodSec: Int? = null,
    ) : OutgoingContent()

    data class ContactCard(
        val firstName: String,
        val lastName: String = "",
        val phone: String? = null,
        val userId: Long? = null,
    ) : OutgoingContent()

    data class Poll(
        val question: String,
        val options: List<String>,
        val anonymous: Boolean = true,
        val multipleAnswers: Boolean = false,
    ) : OutgoingContent()

    data class Sticker(
        val localPath: String,
        val emoji: String = "⭐",
    ) : OutgoingContent()

    data class Ephemeral(
        val body: SanitizedText,
        val expireSeconds: Int,
    ) : OutgoingContent()
}

sealed class BackupExportResult {
    data class Success(val uriOrPath: String, val messageCount: Int, val conversationCount: Int) : BackupExportResult()
    data class Failure(val reason: String) : BackupExportResult()
}

data class Account(
    val id: String,
    val protocol: ProtocolId,
    val displayName: String,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
)

enum class AttachmentState {
    PENDING,
    DOWNLOADING,
    READY,
    FAILED,
}

data class Attachment(
    val id: String,
    val mimeType: String,
    val fileName: String? = null,
    val localPath: String? = null,
    val remoteRef: String? = null,
    val sizeBytes: Long = 0L,
    val state: AttachmentState = AttachmentState.PENDING,
)

data class Conversation(
    val id: String,
    val protocol: ProtocolId,
    val accountId: String,
    val remoteId: String,
    val title: String,
    val lastMessagePreview: String? = null,
    val lastMessageAt: Long = 0L,
    val unreadCount: Int = 0,
)

data class Message(
    val id: String,
    val conversationId: String,
    val protocol: ProtocolId,
    val body: String,
    val timestamp: Long,
    val direction: MessageDirection,
    val deliveryState: DeliveryState = DeliveryState.SENT,
    val senderDisplayName: String? = null,
    val attachments: List<Attachment> = emptyList(),
    val kind: MessageKind = MessageKind.TEXT,
    /** Opaque JSON for location/poll/contact metadata when [kind] is structured. */
    val payloadJson: String? = null,
    /** Disappearing-message timer in seconds; null = permanent. */
    val expireSeconds: Int? = null,
)

@JvmInline
value class SanitizedText(val value: String)

sealed class ConnectionResult {
    data object Success : ConnectionResult()
    data class Failure(val reason: String) : ConnectionResult()
}

/** A single extra field the server requires to finish registration (e.g. a token or email). */
data class RegistrationField(val key: String, val label: String, val secret: Boolean = false)

data class RegistrationRequest(
    val protocol: ProtocolId,
    val server: String,
    val username: String,
    val password: String,
    val extraFields: Map<String, String> = emptyMap(),
)

sealed class RegistrationResult {
    data class Success(val credentials: AccountCredentials) : RegistrationResult()

    /** Server needs more input (e.g. a registration token) before account creation can finish. */
    data class NeedsFields(
        val sessionId: String,
        val fields: List<RegistrationField>,
        val instructions: String? = null,
    ) : RegistrationResult()

    /** Server requires an out-of-band step (captcha/email/terms) completed in a browser. */
    data class NeedsWebView(
        val sessionId: String,
        val url: String,
        val instructions: String? = null,
    ) : RegistrationResult()

    data class Failure(val reason: String) : RegistrationResult()
}

sealed class SendResult {
    data class Success(val messageId: String) : SendResult()
    data class Failure(val reason: String) : SendResult()
}

sealed class HistoryLoadResult {
    data class Success(
        val messageCount: Int,
        val loadedFromCache: Boolean,
        val syncedFromNetwork: Boolean,
    ) : HistoryLoadResult()

    data class Failure(val reason: String) : HistoryLoadResult()
}
