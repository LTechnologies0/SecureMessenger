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
import ltechnologies.onionphone.securemessenger.core.model.Attachment
import ltechnologies.onionphone.securemessenger.core.model.AttachmentState
import ltechnologies.onionphone.securemessenger.core.model.AuthStep
import ltechnologies.onionphone.securemessenger.core.model.AuthStepKind
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

    private val baseCapabilities = ProtocolCapabilities(
        directMessages = true,
        groupChats = true,
        mediaSend = true,
        mediaReceive = true,
        typingIndicators = true,
        readReceipts = true,
        endToEndEncryption = true,
    )

    /** Reflects live session: E2EE and encrypted media require an active Trixnity engine. */
    override val capabilities: ProtocolCapabilities
        get() {
            val e2ee = sessions.values.any { it.e2eeEnabled }
            return baseCapabilities.copy(
                endToEndEncryption = e2ee,
                mediaSend = e2ee,
                mediaReceive = e2ee,
            )
        }

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /** Live session state for one connected Matrix account. */
    private class MatrixSession(
        var trixnityEngine: TrixnityMatrixEngine? = null,
        var httpFallback: MatrixHttpFallback? = null,
        var e2eeEnabled: Boolean = false,
    )

    /**
     * One [MatrixSession] per connected accountId. Two or more Matrix accounts can be logged in
     * simultaneously — connecting account B must never tear down account A's live sync loop,
     * which previously happened because [connect] eagerly disconnected the single shared session.
     */
    private val sessions = java.util.concurrent.ConcurrentHashMap<String, MatrixSession>()
    private val registration = MatrixRegistration()

    private val _pendingAuthStep = MutableStateFlow<AuthStep?>(null)
    fun observePendingAuthStep(): StateFlow<AuthStep?> = _pendingAuthStep.asStateFlow()

    private var pendingSsoAccountId: String? = null
    private var pendingSsoApiBase: String? = null
    private var pendingSsoProxy: ProxyConfig? = null
    private var pendingSsoDisplayName: String? = null
    private var pendingSsoUserHint: String? = null

    private fun clearPendingSso() {
        pendingSsoAccountId = null
        pendingSsoApiBase = null
        pendingSsoProxy = null
        pendingSsoDisplayName = null
        pendingSsoUserHint = null
    }

    fun trixnityEngine(accountId: String? = null): TrixnityMatrixEngine? =
        (accountId?.let { sessions[it] } ?: sessions.values.singleOrNull())?.trixnityEngine

    /** Whether Trixnity E2EE is active for [accountId]. */
    fun usesE2ee(accountId: String? = null): Boolean =
        (accountId?.let { sessions[it] } ?: sessions.values.singleOrNull())?.e2eeEnabled == true

    override fun isAccountConnected(accountId: String): Boolean = sessions.containsKey(accountId)

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
                val rawUserId = account.secrets["userId"]?.trim().orEmpty()
                val matrixUser = if (rawUserId.isBlank()) {
                    ""
                } else {
                    runCatching {
                        MatrixUrls.normalizeUserId(rawUserId, server)
                    }.getOrElse {
                        return@withContext ConnectionResult.Failure(it.message ?: "Invalid Matrix user ID")
                    }
                }
                val password = account.secrets["password"]
                val accessToken = account.secrets["accessToken"]
                val syncSince = account.secrets["syncSince"]

                // Resolve `.well-known/matrix/client` delegation — many servers (e.g. a marketing
                // site delegating to matrix.example.org) 404 on every /_matrix/... call otherwise.
                val apiBaseUrl = MatrixUrls.resolveApiBaseUrl(server, proxy)

                // Reconnecting the SAME account: tear down its old session only.
                disconnect(account.accountId)

                val onSinceUpdated: (String) -> Unit = { batch ->
                    credentialStore.put(account.accountId, SYNC_SINCE_KEY, batch)
                }
                val onAuthExpired: () -> Unit = {
                    credentialStore.put(account.accountId, ACCESS_TOKEN_KEY, "")
                    _connectionState.value = ConnectionState.ERROR
                }

                val flows = MatrixLoginFlows.fetchFlows(apiBaseUrl, proxy)

                // Password path unavailable (OIDC-only homeservers): park SSO auth step.
                if ((accessToken == null || accessToken.isBlank()) &&
                    (password == null || password.isBlank() || !MatrixLoginFlows.supportsPassword(flows)) &&
                    MatrixLoginFlows.supportsSso(flows)
                ) {
                    pendingSsoAccountId = account.accountId
                    pendingSsoApiBase = apiBaseUrl
                    pendingSsoProxy = proxy
                    pendingSsoDisplayName = account.displayName
                    pendingSsoUserHint = matrixUser.takeIf { it.isNotBlank() }
                    _pendingAuthStep.value = AuthStep(
                        kind = AuthStepKind.MATRIX_SSO,
                        prompt = "Connexion SSO Matrix requise",
                        fields = listOf("loginToken"),
                        url = MatrixLoginFlows.ssoRedirectUrl(apiBaseUrl),
                    )
                    _connectionState.value = ConnectionState.CONNECTING
                    return@withContext ConnectionResult.Success
                }

                if (matrixUser.isBlank()) {
                    return@withContext ConnectionResult.Failure(
                        "Matrix user ID requis (sauf connexion SSO)",
                    )
                }

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
                        val passwordResult = fallback.connect(
                            account.accountId,
                            apiBaseUrl,
                            matrixUser,
                            password,
                            proxy,
                            since = syncSince,
                            onSinceUpdated = onSinceUpdated,
                            onAuthExpired = onAuthExpired,
                        )
                        if (passwordResult.isFailure && MatrixLoginFlows.supportsSso(flows)) {
                            pendingSsoAccountId = account.accountId
                            pendingSsoApiBase = apiBaseUrl
                            pendingSsoProxy = proxy
                            pendingSsoDisplayName = account.displayName
                            pendingSsoUserHint = matrixUser
                            _pendingAuthStep.value = AuthStep(
                                kind = AuthStepKind.MATRIX_SSO,
                                prompt = "Mot de passe refusé — connectez-vous via SSO",
                                fields = listOf("loginToken"),
                                url = MatrixLoginFlows.ssoRedirectUrl(apiBaseUrl),
                            )
                            _connectionState.value = ConnectionState.CONNECTING
                            return@withContext ConnectionResult.Success
                        }
                        passwordResult
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

                return@withContext finalizeMatrixSession(
                    accountId = account.accountId,
                    displayName = account.displayName,
                    apiBaseUrl = apiBaseUrl,
                    matrixUser = fallback.persistedUserId ?: matrixUser,
                    accessToken = fallback.persistedAccessToken
                        ?: accessToken?.takeIf { it.isNotBlank() }
                        ?: return@withContext ConnectionResult.Failure("Access token manquant"),
                    password = password,
                    proxy = proxy,
                    fallback = fallback,
                )
            } catch (e: Exception) {
                Timber.e(e, "Matrix connect failed")
                _connectionState.value = ConnectionState.ERROR
                ConnectionResult.Failure(e.message ?: "Matrix connect failed")
            }
        }

    private suspend fun finalizeMatrixSession(
        accountId: String,
        displayName: String,
        apiBaseUrl: String,
        matrixUser: String,
        accessToken: String,
        password: String?,
        proxy: ProxyConfig,
        fallback: MatrixHttpFallback,
    ): ConnectionResult {
        val session = MatrixSession(httpFallback = fallback)
        sessions[accountId] = session

        val engine = TrixnityMatrixEngine(repository, context.filesDir)
        var trixnityLogin = engine.loginWithAccessToken(
            accountId,
            apiBaseUrl,
            matrixUser,
            accessToken,
            proxy,
        )
        if (trixnityLogin.isFailure) {
            Timber.w(trixnityLogin.exceptionOrNull(), "Trixnity first login failed; wiping store and retrying")
            engine.close()
            TrixnityMatrixEngine.wipeAccountStore(context.filesDir, accountId)
            val retryEngine = TrixnityMatrixEngine(repository, context.filesDir)
            trixnityLogin = retryEngine.loginWithAccessToken(
                accountId,
                apiBaseUrl,
                matrixUser,
                accessToken,
                proxy,
            )
            if (trixnityLogin.isSuccess) {
                session.trixnityEngine = retryEngine
            } else {
                retryEngine.close()
            }
        } else {
            session.trixnityEngine = engine
        }

        if (trixnityLogin.isSuccess) {
            session.e2eeEnabled = true
            // Trixnity owns sync + E2EE — stop plaintext HTTP /sync to avoid dual timelines.
            fallback.disconnect()
            session.httpFallback = null
            Timber.i("Matrix Trixnity E2EE session active for $accountId")
        } else {
            session.e2eeEnabled = false
            sessions.remove(accountId)
            fallback.disconnect()
            engine.close()
            _connectionState.value = ConnectionState.ERROR
            return ConnectionResult.Failure(
                "E2EE Matrix (Trixnity) indisponible: " +
                    (trixnityLogin.exceptionOrNull()?.message ?: "login failed"),
            )
        }

        credentialStore.put(accountId, ACCESS_TOKEN_KEY, accessToken)
        password?.let { credentialStore.put(accountId, "password", it) }
        credentialStore.put(accountId, "userId", matrixUser)
        credentialStore.put(accountId, "homeserver", apiBaseUrl)

        repository.upsertAccount(
            ltechnologies.onionphone.securemessenger.core.model.Account(
                id = accountId,
                protocol = ProtocolId.MATRIX,
                displayName = displayName,
                connectionState = ConnectionState.CONNECTED,
            ),
        )
        _pendingAuthStep.value = null
        _connectionState.value = ConnectionState.CONNECTED
        return ConnectionResult.Success
    }

    override suspend fun pendingAuthStep(): AuthStep? = _pendingAuthStep.value

    override suspend fun continueAuthentication(fields: Map<String, String>): ConnectionResult =
        withContext(Dispatchers.IO) {
            when (_pendingAuthStep.value?.kind) {
                AuthStepKind.MATRIX_SSO -> {
                    val loginToken = fields["loginToken"]?.trim().orEmpty()
                    if (loginToken.isBlank()) {
                        return@withContext ConnectionResult.Failure("loginToken SSO manquant")
                    }
                    val apiBase = pendingSsoApiBase
                        ?: return@withContext ConnectionResult.Failure("Session SSO expirée")
                    val proxy = pendingSsoProxy
                        ?: return@withContext ConnectionResult.Failure("Proxy SSO manquant")
                    val accountId = pendingSsoAccountId
                        ?: return@withContext ConnectionResult.Failure("Compte SSO manquant")
                    try {
                        networkGuard.assertNetworkAllowed()
                        // One-shot loginToken: exchange once via CS API, then soft-login Trixnity
                        // (never call m.login.token twice — the token is consumed).
                        val tokenLogin = MatrixLoginFlows.loginWithToken(apiBase, loginToken, proxy)
                        val fallback = MatrixHttpFallback(repository)
                        fallback.connectWithToken(
                            accountId,
                            apiBase,
                            tokenLogin.userId,
                            tokenLogin.accessToken,
                            proxy,
                        ).getOrThrow()
                        finalizeMatrixSession(
                            accountId = accountId,
                            displayName = pendingSsoDisplayName ?: tokenLogin.userId,
                            apiBaseUrl = apiBase,
                            matrixUser = tokenLogin.userId,
                            accessToken = tokenLogin.accessToken,
                            password = null,
                            proxy = proxy,
                            fallback = fallback,
                        ).also { clearPendingSso() }
                    } catch (e: Exception) {
                        Timber.e(e, "Matrix SSO continue failed")
                        clearPendingSso()
                        _connectionState.value = ConnectionState.ERROR
                        ConnectionResult.Failure(e.message ?: "SSO Matrix échoué")
                    }
                }
                else -> ConnectionResult.Failure("Aucune étape d'auth Matrix en attente")
            }
        }

    override fun observeConversations(): Flow<List<Conversation>> = repository.observeConversations()

    override fun observeMessages(conversationId: String): Flow<List<Message>> =
        repository.observeMessages(conversationId)

    override suspend fun startConversation(
        remoteId: String,
        initialMessage: SanitizedText?,
        accountId: String?,
    ): SendResult {
        val accId = accountId ?: sessions.keys.singleOrNull() ?: return SendResult.Failure("Not connected")
        val convId = conversationIdFor(accId, remoteId)
        val title = when {
            remoteId.startsWith("@") -> remoteId
            remoteId.startsWith("!") -> "Conversation Matrix"
            else -> remoteId
        }
        repository.upsertConversation(
            Conversation(
                id = convId,
                protocol = ProtocolId.MATRIX,
                accountId = accId,
                remoteId = remoteId,
                title = title,
            ),
        )
        return if (initialMessage != null) sendMessage(convId, initialMessage, accId) else SendResult.Success(convId)
    }

    override suspend fun sendMessage(conversationId: String, body: SanitizedText, accountId: String?): SendResult =
        withContext(Dispatchers.IO) {
            try {
                networkGuard.assertNetworkAllowed()
                val accId = accountId
                    ?: conversationId.substringBefore('_', missingDelimiterValue = conversationId)
                val session = sessions[accId] ?: return@withContext SendResult.Failure("Account not connected")
                val roomId = conversationId.substringAfter('_', missingDelimiterValue = conversationId)
                session.trixnityEngine?.let {
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
                session.httpFallback?.sendMessage(conversationId, body)
                    ?: SendResult.Failure("Not connected")
            } catch (e: Exception) {
                SendResult.Failure(e.message ?: "Send failed")
            }
        }

    override suspend fun sendMedia(
        conversationId: String,
        attachment: Attachment,
        caption: SanitizedText?,
        accountId: String?,
    ): SendResult = withContext(Dispatchers.IO) {
        try {
            networkGuard.assertNetworkAllowed()
            val accId = accountId
                ?: conversationId.substringBefore('_', missingDelimiterValue = conversationId)
            val session = sessions[accId] ?: return@withContext SendResult.Failure("Account not connected")
            val roomId = conversationId.substringAfter('_', missingDelimiterValue = conversationId)
            val localPath = attachment.localPath
                ?: return@withContext SendResult.Failure("Missing local file path")
            val engine = session.trixnityEngine
                ?: return@withContext SendResult.Failure("Media send requires Trixnity E2EE session")
            val sent = engine.sendMedia(
                roomId = roomId,
                localPath = localPath,
                mimeType = attachment.mimeType,
                fileName = attachment.fileName,
                caption = caption?.value,
            )
            if (sent.isFailure) {
                return@withContext SendResult.Failure(
                    sent.exceptionOrNull()?.message ?: "Media send failed",
                )
            }
            val body = caption?.value ?: attachment.fileName ?: "Attachment"
            val msg = Message(
                id = "${conversationId}_${System.currentTimeMillis()}",
                conversationId = conversationId,
                protocol = ProtocolId.MATRIX,
                body = body,
                timestamp = System.currentTimeMillis(),
                direction = ltechnologies.onionphone.securemessenger.core.model.MessageDirection.OUTGOING,
                deliveryState = ltechnologies.onionphone.securemessenger.core.model.DeliveryState.SENT,
                attachments = listOf(
                    attachment.copy(state = AttachmentState.READY),
                ),
            )
            repository.upsertMessage(msg)
            SendResult.Success(msg.id)
        } catch (e: Exception) {
            SendResult.Failure(e.message ?: "Media send failed")
        }
    }

    override suspend fun disconnect(accountId: String?) {
        withContext(Dispatchers.IO) {
            val toClose = if (accountId != null) {
                sessions.remove(accountId)?.let { listOf(accountId to it) } ?: emptyList()
            } else {
                val all = sessions.entries.map { it.key to it.value }
                sessions.clear()
                all
            }
            toClose.forEach { (id, session) ->
                session.trixnityEngine?.close()
                session.httpFallback?.disconnect()
                repository.upsertAccount(
                    ltechnologies.onionphone.securemessenger.core.model.Account(
                        id = id,
                        protocol = ProtocolId.MATRIX,
                        displayName = id,
                        connectionState = ConnectionState.DISCONNECTED,
                    ),
                )
            }
            if (sessions.isEmpty()) {
                _connectionState.value = ConnectionState.DISCONNECTED
            }
        }
    }

    companion object {
        const val ACCESS_TOKEN_KEY = "accessToken"
        const val SYNC_SINCE_KEY = "syncSince"

        fun conversationIdFor(accountId: String, roomId: String) = "${accountId}_$roomId"
    }
}
