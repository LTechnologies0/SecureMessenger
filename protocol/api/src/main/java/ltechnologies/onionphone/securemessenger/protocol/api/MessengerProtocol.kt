package ltechnologies.onionphone.securemessenger.protocol.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import ltechnologies.onionphone.securemessenger.core.model.AccountCredentials
import ltechnologies.onionphone.securemessenger.core.model.AuthStep
import ltechnologies.onionphone.securemessenger.core.model.ConnectionResult
import ltechnologies.onionphone.securemessenger.core.model.ConnectionState
import ltechnologies.onionphone.securemessenger.core.model.Conversation
import ltechnologies.onionphone.securemessenger.core.model.Message
import ltechnologies.onionphone.securemessenger.core.model.ProtocolCapabilities
import ltechnologies.onionphone.securemessenger.core.model.ProtocolId
import ltechnologies.onionphone.securemessenger.core.model.ProxyConfig
import ltechnologies.onionphone.securemessenger.core.model.SanitizedText
import ltechnologies.onionphone.securemessenger.core.model.HistoryLoadResult
import ltechnologies.onionphone.securemessenger.core.model.RegistrationRequest
import ltechnologies.onionphone.securemessenger.core.model.RegistrationResult
import ltechnologies.onionphone.securemessenger.core.model.SendResult

/**
 * Unified contract for multi-protocol messaging in one inbox.
 * Protocol adapters map Smack / Trixnity / TDLib (and future SDKs) onto this surface.
 */
interface MessengerProtocol {
    /** Stable identifier for this protocol (Matrix, XMPP, Telegram, Discord, Signal). */
    val id: ProtocolId

    /** Feature flags describing what this adapter supports (registration, history sync, etc.). */
    val capabilities: ProtocolCapabilities

    /** Live connection status, observed by the UI to render connected/connecting/error states. */
    val connectionState: StateFlow<ConnectionState>

    /**
     * Authenticates with the server using stored [account] credentials, routed through [proxy]
     * (always a Tor SOCKS5 endpoint in this app). Returns [ConnectionResult.Success],
     * a pending [AuthStep] via [pendingAuthStep], or a failure.
     */
    suspend fun connect(account: AccountCredentials, proxy: ProxyConfig): ConnectionResult

    /** Whether this protocol supports creating a brand new account on the server (vs. login-only). */
    val canRegister: Boolean get() = false

    /** Starts account creation on the server. Default: unsupported. */
    suspend fun register(request: RegistrationRequest, proxy: ProxyConfig): RegistrationResult =
        RegistrationResult.Failure("Registration not supported for $id")

    /** Resumes a registration flow that returned [RegistrationResult.NeedsFields] or [RegistrationResult.NeedsWebView]. */
    suspend fun continueRegistration(
        sessionId: String,
        fields: Map<String, String>,
        proxy: ProxyConfig,
    ): RegistrationResult = RegistrationResult.Failure("Registration not supported for $id")

    /** Returns pending auth step when connect succeeds but user input is still required. */
    suspend fun pendingAuthStep(): AuthStep? = null

    /** Submits [fields] requested by a pending [AuthStep] (e.g. 2FA code, SMS code) and resumes login. */
    suspend fun continueAuthentication(fields: Map<String, String>): ConnectionResult =
        ConnectionResult.Success

    /** Live list of conversations (rooms/chats/DMs) for the connected account. */
    fun observeConversations(): Flow<List<Conversation>>

    /** Live list of messages for [conversationId], newest-appended. */
    fun observeMessages(conversationId: String): Flow<List<Message>>

    /** Starts a new conversation with [remoteId], optionally sending [initialMessage]. */
    suspend fun startConversation(remoteId: String, initialMessage: SanitizedText? = null): SendResult =
        SendResult.Failure("Not supported")

    /** Sends [body] to [conversationId] and returns the delivery outcome. */
    suspend fun sendMessage(conversationId: String, body: SanitizedText): SendResult

    /** Sync full message history when the user opens a conversation. */
    suspend fun loadMessageHistory(conversationId: String): HistoryLoadResult =
        HistoryLoadResult.Success(messageCount = 0, loadedFromCache = false, syncedFromNetwork = false)

    /** Protocol hook when the user leaves a conversation screen. */
    suspend fun closeConversation(conversationId: String) = Unit

    /** Tears down the connection and releases any underlying client/socket resources. */
    suspend fun disconnect()
}

/** Thrown when the app attempts to use a [MessengerProtocol] that hasn't been enabled/configured. */
class ProtocolNotEnabledException(protocol: ProtocolId) :
    IllegalStateException("Protocol $protocol is not enabled")
