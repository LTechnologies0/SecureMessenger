package ltechnologies.onionphone.securemessenger.protocol.matrix

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ltechnologies.onionphone.securemessenger.core.model.ProxyConfig
import timber.log.Timber

/** Normalizes user-supplied Matrix homeserver URLs for Ktor/Trixnity. */
internal object MatrixUrls {
    fun normalizeHomeserver(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        require(trimmed.isNotBlank()) { "Homeserver URL is empty" }
        val withScheme = when {
            trimmed.contains("://") -> trimmed
            else -> "https://$trimmed"
        }
        // Reject accidental proxy hosts passed as homeserver.
        require(!withScheme.contains("127.0.0.1") && !withScheme.contains("localhost")) {
            "Homeserver cannot be localhost — enter your Matrix server (e.g. matrix.org)"
        }
        return withScheme.trimEnd('/')
    }

    fun loginLocalPart(matrixUserId: String): String =
        matrixUserId.trim().removePrefix("@").substringBefore(':')

    fun normalizeUserId(raw: String, homeserver: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("@") && trimmed.contains(':')) return trimmed
        val serverName = homeserver
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
        val localPart = trimmed.removePrefix("@").substringBefore(':')
        require(localPart.isNotBlank()) { "Matrix user ID is empty" }
        return "@$localPart:$serverName"
    }

    /**
     * Resolves the actual Client-Server API base URL for [normalizedServer] via the
     * `.well-known/matrix/client` delegation mechanism (Matrix spec §3.2). Many deployments
     * (e.g. a marketing site at `example.org` delegating to `matrix.example.org`) will 404 on
     * every `/_matrix/...` call unless callers resolve this first. Falls back to
     * [normalizedServer] itself if the well-known lookup is absent, unreachable, or malformed.
     */
    suspend fun resolveApiBaseUrl(normalizedServer: String, proxy: ProxyConfig): String {
        val client = MatrixHttpClientFactory.create(proxy)
        return try {
            val response = client.get("$normalizedServer/.well-known/matrix/client")
            if (!response.status.isSuccess()) return normalizedServer
            val baseUrl = response.body<WellKnownClient>().homeserver?.baseUrl
                ?.trim()
                ?.trimEnd('/')
            if (baseUrl.isNullOrBlank()) normalizedServer else baseUrl
        } catch (e: Exception) {
            Timber.w(e, "Matrix well-known discovery failed for $normalizedServer, using it as-is")
            normalizedServer
        } finally {
            try {
                client.close()
            } catch (_: Exception) {
            }
        }
    }

    @Serializable
    private data class WellKnownClient(
        @SerialName("m.homeserver") val homeserver: HomeserverInfo? = null,
    )

    @Serializable
    private data class HomeserverInfo(
        @SerialName("base_url") val baseUrl: String? = null,
    )
}
