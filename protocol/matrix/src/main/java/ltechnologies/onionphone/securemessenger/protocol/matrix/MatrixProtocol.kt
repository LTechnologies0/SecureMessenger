package ltechnologies.onionphone.securemessenger.protocol.matrix

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import ltechnologies.onionphone.securemessenger.core.model.AccountCredentials
import ltechnologies.onionphone.securemessenger.core.model.ConnectionResult
import ltechnologies.onionphone.securemessenger.core.model.ConnectionState
import ltechnologies.onionphone.securemessenger.core.model.Conversation
import ltechnologies.onionphone.securemessenger.core.model.Message
import ltechnologies.onionphone.securemessenger.core.model.ProtocolCapabilities
import ltechnologies.onionphone.securemessenger.core.model.ProtocolId
import ltechnologies.onionphone.securemessenger.core.model.ProxyConfig
import ltechnologies.onionphone.securemessenger.core.model.RegistrationRequest
import ltechnologies.onionphone.securemessenger.core.model.RegistrationResult
import ltechnologies.onionphone.securemessenger.core.model.SanitizedText
import ltechnologies.onionphone.securemessenger.core.model.SendResult
import ltechnologies.onionphone.securemessenger.core.network.NetworkGuard
import ltechnologies.onionphone.securemessenger.core.security.EncryptedCredentialStore
import ltechnologies.onionphone.securemessenger.data.MessengerRepository
import ltechnologies.onionphone.securemessenger.protocol.api.MessengerProtocol
import timber.log.Timber

@Singleton
class MatrixProtocol @Inject constructor(
    @ApplicationContext private val context: Context,
    private val networkGuard: NetworkGuard,
    private val repository: MessengerRepository,
    private val credentialStore: EncryptedCredentialStore,
) : MessengerProtocol {

    override val id: ProtocolId = ProtocolId.MATRIX

    override val capabilities = ProtocolCapabilities(
        directMessages = true,
        groupChats = true,
        mediaSend = true,
        mediaReceive = true,
        typingIndicators = true,
        readReceipts = true,
        endToEndEncryption = true,
    )

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private var accountId: String? = null
    private var trixnityEngine: TrixnityMatrixEngine? = null
    private var httpFallback: MatrixHttpFallback? = null
    private val registration = MatrixRegistration()

    fun trixnityEngine(): TrixnityMatrixEngine? = trixnityEngine

    override val canRegister: Boolean = true

    override suspend fun register(request: RegistrationRequest, proxy: ProxyConfig): RegistrationResult =
        withContext(Dispatchers.IO) {
            try {
                networkGuard.assertNetworkAllowed()
                val server = MatrixUrls.normalizeHomeserver(request.server)
                val apiBaseUrl = MatrixUrls.resolveApiBaseUrl(server, proxy)
                registration.register(apiBaseUrl, request.username.trim(), request.password, proxy)
            } catch (e: Exception) {
                Timber.e(e, "Matrix registration failed")
                RegistrationResult.Failure(e.message ?: "Matrix registration failed")
            }
        }

    override suspend fun continueRegistration(
        sessionId: String,
        fields: Map<String, String>,
        proxy: ProxyConfig,
    ): RegistrationResult =
        withContext(Dispatchers.IO) {
            try {
                networkGuard.assertNetworkAllowed()
                registration.continueRegistration(sessionId, fields, proxy)
            } catch (e: Exception) {
                Timber.e(e, "Matrix registration (continue) failed")
                RegistrationResult.Failure(e.message ?: "Matrix registration failed")
            }
        }

    override suspend fun connect(account: AccountCredentials, proxy: ProxyConfig): ConnectionResult =
        withContext(Dispatchers.IO) {
            try {
                networkGuard.assertNetworkAllowed()
                _connectionState.value = ConnectionState.CONNECTING

                val server = runCatching {
                    MatrixUrls.normalizeHomeserver(
                        account.secrets["homeserver"] ?: error("Missing homeserver URL"),
                    )
                }.getOrElse {
                    return@withContext ConnectionResult.Failure(it.message ?: "Invalid homeserver URL")
                }
                val matrixUser = runCatching {
                    MatrixUrls.normalizeUserId(
                        account.secrets["userId"] ?: error("Missing Matrix user ID"),
                        server,
                    )
                }.getOrElse {
                    return@withContext ConnectionResult.Failure(it.message ?: "Invalid Matrix user ID")
                }
                val password = account.secrets["password"]
                val accessToken = account.secrets["accessToken"]
                val syncSince = account.secrets["syncSince"]

                // Resolve `.well-known/matrix/client` delegation — many servers (e.g. a marketing
                // site delegating to matrix.example.org) 404 on every /_matrix/... call otherwise.
                val apiBaseUrl = MatrixUrls.resolveApiBaseUrl(server, proxy)

                disconnect()
                accountId = account.accountId

                val onSinceUpdated: (String) -> Unit = { batch ->
                    credentialStore.put(account.accountId, SYNC_SINCE_KEY, batch)
                }
                val onAuthExpired: () -> Unit = {
                    credentialStore.put(account.accountId, ACCESS_TOKEN_KEY, "")
                    _connectionState.value = ConnectionState.ERROR
                }

                // Password login via CS API first — Trixnity on matrix.org triggers MSC2965/OIDC
                // with an http://localhost callback, which Android cleartext policy blocks.
                val fallback = MatrixHttpFallback(repository)
                val fb = when {
                    accessToken != null && accessToken.isNotBlank() -> {
                        fallback.connectWithToken(
                            account.accountId,
                            apiBaseUrl,
                            matrixUser,
                            accessToken,
                            proxy,
                            since = syncSince,
                            onSinceUpdated = onSinceUpdated,
                            onAuthExpired = onAuthExpired,
                        )
                    }
                    password != null -> {
                        fallback.connect(
                            account.accountId,
                            apiBaseUrl,
                            matrixUser,
                            password,
                            proxy,
                            since = syncSince,
                            onSinceUpdated = onSinceUpdated,
                            onAuthExpired = onAuthExpired,
                        )
                    }
                    else -> Result.failure(IllegalStateException("Missing Matrix credentials"))
                }
                if (fb.isFailure) {
                    _connectionState.value = ConnectionState.ERROR
                    val cause = fb.exceptionOrNull()
                    Timber.e(cause, "Matrix login failed")
                    return@withContext ConnectionResult.Failure(
                        cause?.message ?: "Matrix connect failed",
                    )
                }
                httpFallback = fallback
                fallback.persistedAccessToken?.let { token ->
                    credentialStore.put(account.accountId, ACCESS_TOKEN_KEY, token)
                }
                password?.let { credentialStore.put(account.accountId, "password", it) }
                credentialStore.put(account.accountId, "userId", matrixUser)
                // Cache the resolved API base URL so future reconnects skip the well-known lookup.
                credentialStore.put(account.accountId, "homeserver", apiBaseUrl)

                repository.upsertAccount(
                    ltechnologies.onionphone.securemessenger.core.model.Account(
                        id = account.accountId,
                        protocol = ProtocolId.MATRIX,
                        displayName = account.displayName,
                        connectionState = ConnectionState.CONNECTED,
                    ),
                )
                _connectionState.value = ConnectionState.CONNECTED
                ConnectionResult.Success
            } catch (e: Exception) {
                Timber.e(e, "Matrix connect failed")
                _connectionState.value = ConnectionState.ERROR
                ConnectionResult.Failure(e.message ?: "Matrix connect failed")
            }
        }

    override fun observeConversations(): Flow<List<Conversation>> = repository.observeConversations()

    override fun observeMessages(conversationId: String): Flow<List<Message>> =
        repository.observeMessages(conversationId)

    override suspend fun startConversation(remoteId: String, initialMessage: SanitizedText?): SendResult {
        val accId = accountId ?: return SendResult.Failure("Not connected")
        val convId = conversationIdFor(accId, remoteId)
        repository.upsertConversation(
            Conversation(
                id = convId,
                protocol = ProtocolId.MATRIX,
                accountId = accId,
                remoteId = remoteId,
                title = remoteId,
            ),
        )
        return if (initialMessage != null) sendMessage(convId, initialMessage) else SendResult.Success(convId)
    }

    override suspend fun sendMessage(conversationId: String, body: SanitizedText): SendResult =
        withContext(Dispatchers.IO) {
            try {
                networkGuard.assertNetworkAllowed()
                val roomId = conversationId.substringAfter('_', missingDelimiterValue = conversationId)
                trixnityEngine?.let {
                    it.sendText(roomId, body.value)
                    val msg = Message(
                        id = "${conversationId}_${System.currentTimeMillis()}",
                        conversationId = conversationId,
                        protocol = ProtocolId.MATRIX,
                        body = body.value,
                        timestamp = System.currentTimeMillis(),
                        direction = ltechnologies.onionphone.securemessenger.core.model.MessageDirection.OUTGOING,
                        deliveryState = ltechnologies.onionphone.securemessenger.core.model.DeliveryState.SENT,
                    )
                    repository.upsertMessage(msg)
                    return@withContext SendResult.Success(msg.id)
                }
                httpFallback?.sendMessage(conversationId, body)
                    ?: SendResult.Failure("Not connected")
            } catch (e: Exception) {
                SendResult.Failure(e.message ?: "Send failed")
            }
        }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            trixnityEngine?.close()
            trixnityEngine = null
            httpFallback?.disconnect()
            httpFallback = null
            accountId?.let { id ->
                repository.upsertAccount(
                    ltechnologies.onionphone.securemessenger.core.model.Account(
                        id = id,
                        protocol = ProtocolId.MATRIX,
                        displayName = id,
                        connectionState = ConnectionState.DISCONNECTED,
                    ),
                )
            }
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    companion object {
        const val ACCESS_TOKEN_KEY = "accessToken"
        const val SYNC_SINCE_KEY = "syncSince"

        fun conversationIdFor(accountId: String, roomId: String) = "${accountId}_$roomId"
    }
}
