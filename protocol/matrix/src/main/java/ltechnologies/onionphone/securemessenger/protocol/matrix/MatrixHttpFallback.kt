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
import ltechnologies.onionphone.securemessenger.core.model.DeliveryState
import ltechnologies.onionphone.securemessenger.core.model.Message
import ltechnologies.onionphone.securemessenger.core.model.MessageDirection
import ltechnologies.onionphone.securemessenger.core.model.ProtocolId
import ltechnologies.onionphone.securemessenger.core.model.ProxyConfig
import ltechnologies.onionphone.securemessenger.core.model.SanitizedText
import ltechnologies.onionphone.securemessenger.core.model.SendResult
import ltechnologies.onionphone.securemessenger.data.MessengerRepository

/** CS API fallback when Trixnity login fails — supports m.login.password and m.login.token. */
class MatrixHttpFallback(private val repository: MessengerRepository) {
    private var accountId: String? = null
    private var accessToken: String? = null
    private var matrixUserId: String? = null
    private var homeserver: String? = null
    private var httpClient: HttpClient? = null
    private val syncEngine = MatrixSyncEngine(repository)

    val persistedAccessToken: String? get() = accessToken
    val persistedUserId: String? get() = matrixUserId

    suspend fun connect(
        accId: String,
        server: String,
        matrixUser: String,
        password: String,
        proxy: ProxyConfig,
        since: String? = null,
        onSinceUpdated: (String) -> Unit = {},
        onAuthExpired: () -> Unit = {},
    ): Result<Unit> = runCatching {
        accountId = accId
        homeserver = server
        val client = createProxiedClient(proxy)
        httpClient = client
        val localPart = MatrixUrls.loginLocalPart(matrixUser)
        val httpResponse = client.post("$server/_matrix/client/v3/login") {
            contentType(ContentType.Application.Json)
            setBody(
                LoginRequest(
                    type = "m.login.password",
                    identifier = Identifier(user = localPart),
                    password = password,
                    initialDeviceDisplayName = "SecureMessenger",
                ),
            )
        }
        // Surface the real Matrix error (errcode/error) instead of a cryptic
        // "access_token missing" deserialization failure on non-2xx responses.
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
        syncEngine.start(
            accId,
            response.userId,
            server,
            response.accessToken,
            client,
            since = since,
            onSinceUpdated = onSinceUpdated,
            onAuthExpired = onAuthExpired,
        )
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
    ): Result<Unit> = runCatching {
        accountId = accId
        homeserver = server
        accessToken = token
        matrixUserId = userId
        val client = createProxiedClient(proxy)
        httpClient = client
        syncEngine.start(
            accId,
            userId,
            server,
            token,
            client,
            since = since,
            onSinceUpdated = onSinceUpdated,
            onAuthExpired = onAuthExpired,
        )
    }

    suspend fun sendMessage(conversationId: String, body: SanitizedText): SendResult {
        val token = accessToken ?: return SendResult.Failure("Not connected")
        val server = homeserver ?: return SendResult.Failure("No homeserver")
        val roomId = conversationId.substringAfter('_', missingDelimiterValue = conversationId)
        val txnId = System.currentTimeMillis().toString()
        val client = httpClient ?: return SendResult.Failure("No HTTP client")
        client.post("$server/_matrix/client/v3/rooms/${encodeRoomId(roomId)}/send/m.room.message/$txnId") {
            contentType(ContentType.Application.Json)
            headers.append("Authorization", "Bearer $token")
            setBody(mapOf("msgtype" to "m.text", "body" to body.value))
        }
        val msg = Message(
            id = "${conversationId}_$txnId",
            conversationId = conversationId,
            protocol = ProtocolId.MATRIX,
            body = body.value,
            timestamp = System.currentTimeMillis(),
            direction = MessageDirection.OUTGOING,
            deliveryState = DeliveryState.SENT,
        )
        repository.upsertMessage(msg)
        return SendResult.Success(msg.id)
    }

    fun disconnect() {
        syncEngine.stop()
        try {
            httpClient?.close()
        } catch (_: Exception) {
        }
        httpClient = null
        accessToken = null
        matrixUserId = null
    }

    private fun createProxiedClient(proxy: ProxyConfig): HttpClient = MatrixHttpClientFactory.create(proxy)

    private fun encodeRoomId(roomId: String): String =
        java.net.URLEncoder.encode(roomId, Charsets.UTF_8.name())

    @Serializable
    private data class LoginRequest(
        val type: String,
        val identifier: Identifier,
        val password: String,
        @SerialName("initial_device_display_name") val initialDeviceDisplayName: String? = null,
    )

    @Serializable
    private data class Identifier(val type: String = "m.id.user", val user: String)

    @Serializable
    private data class LoginResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("user_id") val userId: String,
    )

    @Serializable
    private data class MatrixError(
        val errcode: String? = null,
        val error: String? = null,
    )
}
