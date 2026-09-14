package ltechnologies.onionphone.securemessenger.protocol.matrix

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ltechnologies.onionphone.securemessenger.core.model.ProxyConfig
import ltechnologies.onionphone.securemessenger.data.MessengerRepository

/**
 * CS API login helper — password / access-token exchange only.
 * Timeline sync is owned exclusively by Trixnity (E2EE fail-closed); this class must not start
 * plaintext `/sync`.
 */
class MatrixHttpFallback(
    @Suppress("unused") private val repository: MessengerRepository,
) {
    private var accessToken: String? = null
    private var matrixUserId: String? = null
    private var deviceId: String? = null
    private var httpClient: HttpClient? = null

    val persistedAccessToken: String? get() = accessToken
    val persistedUserId: String? get() = matrixUserId
    /** Homeserver-issued device_id bound to [persistedAccessToken] (CS login response). */
    val persistedDeviceId: String? get() = deviceId

    suspend fun connect(
        accId: String,
        server: String,
        matrixUser: String,
        password: String,
        proxy: ProxyConfig,
        since: String? = null,
        onSinceUpdated: (String) -> Unit = {},
        onAuthExpired: () -> Unit = {},
        /** When set, ask the HS to reuse this device (Element password re-login). */
        reuseDeviceId: String? = null,
    ): Result<Unit> = runCatching {
        // Signature kept for call sites; sync callbacks unused (Trixnity owns sync).
        @Suppress("UNUSED_VARIABLE")
        val unused = listOf(accId, since, onSinceUpdated, onAuthExpired)
        val client = createProxiedClient(proxy)
        httpClient = client
        val localPart = MatrixUrls.loginLocalPart(matrixUser)
        val loginUrl = "${server.trimEnd('/')}/_matrix/client/v3/login"
        timber.log.Timber.d("Matrix password login -> $loginUrl device=%s", reuseDeviceId)
        val httpResponse = client.post(loginUrl) {
            contentType(ContentType.Application.Json)
            setBody(
                LoginRequest(
                    type = "m.login.password",
                    identifier = Identifier(user = localPart),
                    password = password,
                    initialDeviceDisplayName = "SecureMessenger",
                    deviceId = reuseDeviceId?.takeIf { it.isNotBlank() },
                ),
            )
        }
        if (!httpResponse.status.isSuccess()) {
            val err = runCatching { httpResponse.body<MatrixError>() }.getOrNull()
            error(
                "Matrix login failed (HTTP ${httpResponse.status.value})" +
                    (err?.errcode?.let { ": $it" } ?: "") +
                    (err?.error?.let { " — $it" } ?: ""),
            )
        }
        val response = httpResponse.body<LoginResponse>()
        accessToken = response.accessToken
        matrixUserId = response.userId
        deviceId = response.deviceId
    }

    suspend fun connectWithToken(
        accId: String,
        server: String,
        userId: String,
        token: String,
        proxy: ProxyConfig,
        since: String? = null,
        onSinceUpdated: (String) -> Unit = {},
        onAuthExpired: () -> Unit = {},
        matrixDeviceId: String? = null,
    ): Result<Unit> = runCatching {
        @Suppress("UNUSED_VARIABLE")
        val unused = listOf(accId, server, since, onSinceUpdated, onAuthExpired)
        accessToken = token
        matrixUserId = userId
        deviceId = matrixDeviceId
        httpClient = createProxiedClient(proxy)
    }

    fun disconnect() {
        try {
            httpClient?.close()
        } catch (_: Exception) {
        }
        httpClient = null
        accessToken = null
        matrixUserId = null
        deviceId = null
    }

    private fun createProxiedClient(proxy: ProxyConfig): HttpClient = MatrixHttpClientFactory.create(proxy)

    @Serializable
    private data class LoginRequest(
        val type: String,
        val identifier: Identifier,
        val password: String,
        @SerialName("initial_device_display_name") val initialDeviceDisplayName: String? = null,
        /** Reclaim the same E2EE device on password re-login (Element-style). */
        @SerialName("device_id") val deviceId: String? = null,
    )

    @Serializable
    private data class Identifier(val type: String = "m.id.user", val user: String)

    @Serializable
    private data class LoginResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("user_id") val userId: String,
        @SerialName("device_id") val deviceId: String? = null,
    )

    @Serializable
    private data class MatrixError(
        val errcode: String? = null,
        val error: String? = null,
    )
}
