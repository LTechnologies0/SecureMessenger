package ltechnologies.onionphone.securemessenger.protocol.matrix

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.net.URLEncoder
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ltechnologies.onionphone.securemessenger.core.model.MatrixSsoRedirect
import ltechnologies.onionphone.securemessenger.core.model.ProxyConfig

/**
 * Matrix Client-Server login flow helpers — password, token, and SSO (m.login.sso).
 */
internal object MatrixLoginFlows {
    const val SSO_REDIRECT_URI = MatrixSsoRedirect.URI

    @Serializable
    data class LoginFlowList(
        val flows: List<LoginFlow> = emptyList(),
    )

    @Serializable
    data class LoginFlow(
        val type: String,
    )

    @Serializable
    data class TokenLoginRequest(
        val type: String = "m.login.token",
        val token: String,
        @SerialName("initial_device_display_name") val initialDeviceDisplayName: String? = "SecureMessenger",
    )

    @Serializable
    data class TokenLoginResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("user_id") val userId: String,
        @SerialName("device_id") val deviceId: String? = null,
    )

    suspend fun fetchFlows(apiBaseUrl: String, proxy: ProxyConfig): List<String> {
        val client = MatrixHttpClientFactory.create(proxy)
        return try {
            val response = client.get("${apiBaseUrl.trimEnd('/')}/_matrix/client/v3/login")
            if (!response.status.isSuccess()) return emptyList()
            response.body<LoginFlowList>().flows.map { it.type }
        } catch (_: Exception) {
            emptyList()
        } finally {
            client.close()
        }
    }

    fun supportsPassword(flows: List<String>): Boolean =
        flows.isEmpty() || flows.any { it == "m.login.password" }

    fun supportsSso(flows: List<String>): Boolean =
        flows.any { it == "m.login.sso" || it == "m.login.token" }

    fun ssoRedirectUrl(apiBaseUrl: String): String {
        val redirect = URLEncoder.encode(SSO_REDIRECT_URI, Charsets.UTF_8.name())
        return "${apiBaseUrl.trimEnd('/')}/_matrix/client/v3/login/sso/redirect?redirectUrl=$redirect"
    }

    fun extractLoginToken(redirectUrl: String): String? =
        MatrixSsoRedirect.extractLoginToken(redirectUrl)

    suspend fun loginWithToken(
        apiBaseUrl: String,
        loginToken: String,
        proxy: ProxyConfig,
    ): TokenLoginResponse {
        val client = MatrixHttpClientFactory.create(proxy)
        try {
            val httpResponse = client.post("${apiBaseUrl.trimEnd('/')}/_matrix/client/v3/login") {
                contentType(ContentType.Application.Json)
                setBody(TokenLoginRequest(token = loginToken))
            }
            if (!httpResponse.status.isSuccess()) {
                val err = runCatching { httpResponse.body<MatrixHttpError>() }.getOrNull()
                error(
                    "Matrix SSO token login failed (HTTP ${httpResponse.status.value})" +
                        (err?.errcode?.let { ": $it" } ?: "") +
                        (err?.error?.let { " — $it" } ?: ""),
                )
            }
            return httpResponse.body()
        } finally {
            client.close()
        }
    }

    @Serializable
    private data class MatrixHttpError(
        val errcode: String? = null,
        val error: String? = null,
    )
}
