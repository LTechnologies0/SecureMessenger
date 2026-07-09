package ltechnologies.onionphone.securemessenger.protocol.matrix

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ltechnologies.onionphone.securemessenger.core.model.AccountCredentials
import ltechnologies.onionphone.securemessenger.core.model.ProtocolId
import ltechnologies.onionphone.securemessenger.core.model.ProxyConfig
import ltechnologies.onionphone.securemessenger.core.model.RegistrationField
import ltechnologies.onionphone.securemessenger.core.model.RegistrationResult

/**
 * Raw Matrix CS API registration (`POST /register` + User-Interactive Auth, per the Matrix spec).
 * Bypasses Trixnity for the same reason as [MatrixHttpFallback]: Trixnity's MSC2965/OIDC flow uses
 * an `http://localhost` callback which Android's cleartext policy blocks.
 */
internal class MatrixRegistration {

    private data class PendingRegistration(
        val server: String,
        val username: String,
        val password: String,
        val session: String,
        val pendingStage: String,
    )

    /** Keyed by our own sessionId (not the Matrix `session`), since the caller only knows ours. */
    private val pending = mutableMapOf<String, PendingRegistration>()

    suspend fun checkUsernameAvailable(server: String, username: String, proxy: ProxyConfig): Result<Boolean> =
        runCatching {
            val client = MatrixHttpClientFactory.create(proxy)
            try {
                val response = client.get("$server/_matrix/client/v3/register/available") {
                    parameter("username", username)
                }
                if (!response.status.isSuccess()) {
                    val err = runCatching { response.body<UiaErrorBody>() }.getOrNull()
                    error(err?.error ?: "Username unavailable")
                }
                response.body<AvailableResponse>().available
            } finally {
                client.close()
            }
        }

    suspend fun register(
        server: String,
        username: String,
        password: String,
        proxy: ProxyConfig,
    ): RegistrationResult {
        val client = MatrixHttpClientFactory.create(proxy)
        return try {
            attemptRegister(client, server, username, password, auth = null)
        } finally {
            client.close()
        }
    }

    suspend fun continueRegistration(
        sessionId: String,
        fields: Map<String, String>,
        proxy: ProxyConfig,
    ): RegistrationResult {
        val state = pending[sessionId]
            ?: return RegistrationResult.Failure("Registration session expired — please restart registration")
        val client = MatrixHttpClientFactory.create(proxy)
        return try {
            val auth = when (state.pendingStage) {
                "m.login.registration_token" -> {
                    val token = fields["registration_token"]
                        ?: return RegistrationResult.Failure("Missing registration token")
                    AuthDict(type = state.pendingStage, session = state.session, token = token)
                }
                else -> AuthDict(type = state.pendingStage, session = state.session)
            }
            attemptRegister(client, state.server, state.username, state.password, auth, existingSessionId = sessionId)
        } finally {
            client.close()
        }
    }

    private suspend fun attemptRegister(
        client: HttpClient,
        server: String,
        username: String,
        password: String,
        auth: AuthDict?,
        existingSessionId: String? = null,
    ): RegistrationResult {
        val httpResponse = client.post("$server/_matrix/client/v3/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequestBody(
                    username = username,
                    password = password,
                    auth = auth,
                    initialDeviceDisplayName = "SecureMessenger",
                ),
            )
        }

        if (httpResponse.status.value == 401) {
            val uia = runCatching { httpResponse.body<UiaErrorBody>() }.getOrNull()
                ?: return RegistrationResult.Failure("Matrix registration failed: invalid auth response")
            val firstFlow = uia.flows.firstOrNull()
                ?: return RegistrationResult.Failure(uia.error ?: "Server rejected registration (no auth flow offered)")
            val remainingStages = firstFlow.stages.filterNot { it in uia.completed.orEmpty() }
            val nextStage = remainingStages.firstOrNull()
                ?: return RegistrationResult.Failure(uia.error ?: "Registration stalled — no remaining auth stage")

            val sessionId = existingSessionId ?: UUID.randomUUID().toString()
            pending[sessionId] = PendingRegistration(server, username, password, uia.session, nextStage)

            return when (nextStage) {
                // Open registration — the server just wants an explicit ack, no extra data needed.
                "m.login.dummy" -> attemptRegister(
                    client,
                    server,
                    username,
                    password,
                    AuthDict(type = nextStage, session = uia.session),
                    existingSessionId = sessionId,
                )
                "m.login.registration_token" -> RegistrationResult.NeedsFields(
                    sessionId,
                    listOf(RegistrationField("registration_token", "Registration token")),
                    instructions = "This server requires an invite/registration token.",
                )
                // Captcha, email, terms, msisdn, etc. — no native UI for these; hand off to a
                // Tor-routed WebView pointed at the server's UIA fallback endpoint.
                else -> RegistrationResult.NeedsWebView(
                    sessionId,
                    "$server/_matrix/client/v3/auth/$nextStage/fallback/web?session=${uia.session}",
                    instructions = "Complete the required step in the browser, then tap Continue.",
                )
            }
        }

        if (!httpResponse.status.isSuccess()) {
            val err = runCatching { httpResponse.body<UiaErrorBody>() }.getOrNull()
            return RegistrationResult.Failure(
                "Matrix registration failed (HTTP ${httpResponse.status.value})" +
                    (err?.errcode?.let { ": $it" } ?: "") +
                    (err?.error?.let { " — $it" } ?: ""),
            )
        }

        val success = httpResponse.body<RegisterSuccessBody>()
        existingSessionId?.let { pending.remove(it) }
        return RegistrationResult.Success(
            AccountCredentials(
                protocol = ProtocolId.MATRIX,
                accountId = UUID.randomUUID().toString(),
                displayName = success.userId,
                secrets = mapOf(
                    "homeserver" to server,
                    "userId" to success.userId,
                    "password" to password,
                    "accessToken" to (success.accessToken ?: ""),
                ),
            ),
        )
    }

    @Serializable
    private data class RegisterRequestBody(
        val username: String,
        val password: String,
        val auth: AuthDict? = null,
        @SerialName("initial_device_display_name") val initialDeviceDisplayName: String? = null,
    )

    @Serializable
    private data class AuthDict(
        val type: String,
        val session: String,
        val token: String? = null,
    )

    @Serializable
    private data class Flow(val stages: List<String> = emptyList())

    @Serializable
    private data class UiaErrorBody(
        val session: String = "",
        val flows: List<Flow> = emptyList(),
        val completed: List<String>? = null,
        val errcode: String? = null,
        val error: String? = null,
    )

    @Serializable
    private data class RegisterSuccessBody(
        @SerialName("user_id") val userId: String,
        @SerialName("access_token") val accessToken: String? = null,
        @SerialName("device_id") val deviceId: String? = null,
    )

    @Serializable
    private data class AvailableResponse(val available: Boolean = false)
}
