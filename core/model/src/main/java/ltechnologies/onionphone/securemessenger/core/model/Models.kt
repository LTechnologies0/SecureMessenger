package ltechnologies.onionphone.securemessenger.core.model

enum class ProtocolId {
    XMPP,
    MATRIX,
    TELEGRAM,
    DISCORD,
    SIGNAL,
}

object FeatureFlags {
    val enabled: Set<ProtocolId> = setOf(
        ProtocolId.XMPP,
        ProtocolId.MATRIX,
        ProtocolId.TELEGRAM,
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
    /** Guardian Project Orbot (`org.torproject.android`). */
    ORBOT,
    /** InviZible Pro (`pan.alexander.tordnscrypt.*`). */
    INVIZIBLE,
    /** Manual SOCKS5 endpoint (any Tor client). */
    CUSTOM,
}

data class ProxyConfig(
    val host: String,
    val port: Int,
    val username: String? = null,
    val password: String? = null,
    val torRequired: Boolean = true,
    val remoteDns: Boolean = true,
    val torProvider: TorProvider = TorProvider.CUSTOM,
)

enum class AuthStepKind {
    NONE,
    TELEGRAM_SMS_CODE,
    TELEGRAM_PASSWORD,
    TELEGRAM_REGISTRATION,
    TELEGRAM_OTHER_DEVICE,
    MATRIX_SSO,
}

data class AuthStep(
    val kind: AuthStepKind,
    val prompt: String,
    val fields: List<String> = emptyList(),
)

data class ProtocolCapabilities(
    val directMessages: Boolean = true,
    val groupChats: Boolean = false,
    val mediaSend: Boolean = false,
    val mediaReceive: Boolean = false,
    val typingIndicators: Boolean = false,
    val readReceipts: Boolean = false,
    val endToEndEncryption: Boolean = false,
    val requiresPhoneAuth: Boolean = false,
)

data class Account(
    val id: String,
    val protocol: ProtocolId,
    val displayName: String,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
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
