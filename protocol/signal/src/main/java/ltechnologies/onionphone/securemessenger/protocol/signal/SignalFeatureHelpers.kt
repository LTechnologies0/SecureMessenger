package ltechnologies.onionphone.securemessenger.protocol.signal

import android.util.Base64
import java.security.SecureRandom
import java.util.Optional
import ltechnologies.onionphone.securemessenger.core.model.MessageKind
import org.json.JSONArray
import org.json.JSONObject
import org.signal.core.util.Base64 as SignalBase64
import org.signal.libsignal.zkgroup.profiles.ProfileKey
import org.signal.network.NetworkResult
import org.signal.network.websocket.WebSocketRequestMessage
import org.whispersystems.signalservice.api.cds.CdsiV2Service
import org.whispersystems.signalservice.api.crypto.ProfileCipher
import org.whispersystems.signalservice.api.fromWebSocketRequest
import org.whispersystems.signalservice.api.profiles.SignalServiceProfile
import org.whispersystems.signalservice.api.websocket.SignalWebSocket
import org.whispersystems.signalservice.internal.push.CdsiAuthResponse
import org.whispersystems.signalservice.internal.push.DataMessage
import timber.log.Timber

internal object SignalFeatureHelpers {
    fun generateProfileKey(): ProfileKey {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return ProfileKey(bytes)
    }

    fun encodeProfileKey(key: ProfileKey): String =
        Base64.encodeToString(key.serialize(), Base64.NO_WRAP)

    fun decodeProfileKey(encoded: String?): ProfileKey? {
        if (encoded.isNullOrBlank()) return null
        return runCatching {
            ProfileKey(Base64.decode(encoded, Base64.NO_WRAP))
        }.getOrNull()
    }

    fun fetchCdsiAuth(authWebSocket: SignalWebSocket.AuthenticatedWebSocket): CdsiAuthResponse? {
        return runCatching {
            val request = WebSocketRequestMessage(verb = "GET", path = "/v2/directory/auth")
            NetworkResult.fromWebSocketRequest(authWebSocket, request, CdsiAuthResponse::class)
                .successOrThrow()
        }.onFailure { Timber.w(it, "CDSI auth failed") }.getOrNull()
    }

    fun lookupRegisteredUsers(
        session: SignalSessionContext,
        e164s: Set<String>,
        previousToken: ByteArray?,
        tokenSaver: (ByteArray) -> Unit,
    ): Map<String, CdsiV2Service.ResponseItem> {
        if (e164s.isEmpty()) return emptyMap()
        val auth = fetchCdsiAuth(session.authWebSocket) ?: return emptyMap()
        val username = auth.username ?: return emptyMap()
        val password = auth.password ?: return emptyMap()
        val request = CdsiV2Service.Request(
            emptySet(),
            e164s,
            emptyMap(),
            Optional.ofNullable(previousToken),
        )
        val cdsi = CdsiV2Service(session.network)
        val result = cdsi.getRegisteredUsers(username, password, request) { token ->
            if (token != null && token.isNotEmpty()) tokenSaver(token)
        }.blockingGet()
        return when (result) {
            is NetworkResult.Success -> result.result.results
            else -> {
                Timber.w("CDSI lookup failed: %s", result)
                emptyMap()
            }
        }
    }

    fun decryptProfileField(profileKey: ProfileKey, encoded: String?): String? {
        if (encoded.isNullOrBlank()) return null
        return runCatching {
            val cipher = ProfileCipher(profileKey)
            val bytes = SignalBase64.decodeOrNull(encoded) ?: return null
            cipher.decryptString(bytes).trim().ifBlank { null }
        }.onFailure { Timber.d(it, "Profile field decrypt failed") }.getOrNull()
    }

    fun decryptProfile(profileKey: ProfileKey, profile: SignalServiceProfile): Pair<String?, String?> {
        val name = decryptProfileField(profileKey, profile.name)
        val about = decryptProfileField(profileKey, profile.about)
        return name to about
    }

    /**
     * Classifies inbound DataMessage into [MessageKind] + optional payload JSON + expire timer.
     */
    fun classifyDataMessage(
        dataMessage: DataMessage,
        hasVoiceAttachment: Boolean,
    ): Triple<MessageKind, String?, Int?> {
        val expire = dataMessage.expireTimer?.takeIf { it > 0 }
        val payload = JSONObject()
        var kind = MessageKind.TEXT

        dataMessage.quote?.let { quote ->
            payload.put(
                "quote",
                JSONObject()
                    .put("id", quote.id)
                    .put("authorAci", quote.authorAci)
                    .put("text", quote.text),
            )
        }
        dataMessage.reaction?.let { reaction ->
            payload.put(
                "reaction",
                JSONObject()
                    .put("emoji", reaction.emoji)
                    .put("remove", reaction.remove == true)
                    .put("targetAuthorAci", reaction.targetAuthorAci)
                    .put("targetSentTimestamp", reaction.targetSentTimestamp),
            )
            kind = MessageKind.SYSTEM
        }
        dataMessage.sticker?.let { sticker ->
            payload.put(
                "sticker",
                JSONObject()
                    .put("emoji", sticker.emoji)
                    .put("stickerId", sticker.stickerId)
                    .put("packId", sticker.packId?.let { Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP) }),
            )
            kind = MessageKind.STICKER
        }
        dataMessage.pollCreate?.let { poll ->
            val options = JSONArray()
            poll.options.forEach { options.put(it) }
            payload.put(
                "poll",
                JSONObject()
                    .put("question", poll.question)
                    .put("allowMultiple", poll.allowMultiple == true)
                    .put("options", options),
            )
            kind = MessageKind.POLL
        }
        if (dataMessage.contact.isNotEmpty()) {
            val contacts = JSONArray()
            dataMessage.contact.forEach { contact ->
                val name = listOfNotNull(
                    contact.name?.givenName,
                    contact.name?.familyName,
                ).joinToString(" ").ifBlank {
                    contact.name?.nickname.orEmpty()
                }
                contacts.put(
                    JSONObject()
                        .put("name", name)
                        .put("phone", contact.number.firstOrNull()?.value_)
                        .put("organization", contact.organization),
                )
            }
            payload.put("contacts", contacts)
            if (kind == MessageKind.TEXT) kind = MessageKind.CONTACT
        }
        if (hasVoiceAttachment && kind == MessageKind.TEXT) {
            kind = MessageKind.VOICE
        }
        val bodyHint = dataMessage.body?.trim().orEmpty()
        if (kind == MessageKind.TEXT && bodyHint.startsWith("geo:", ignoreCase = true)) {
            kind = MessageKind.LOCATION
            val coords = bodyHint.removePrefix("geo:").removePrefix("GEO:").substringBefore(';')
            val parts = coords.split(',')
            if (parts.size >= 2) {
                payload.put(
                    "location",
                    JSONObject()
                        .put("latitude", parts[0].toDoubleOrNull())
                        .put("longitude", parts[1].toDoubleOrNull()),
                )
            }
        }
        val isExpirationUpdate = (dataMessage.flags ?: 0) and
            (DataMessage.Flags.EXPIRATION_TIMER_UPDATE.value) != 0
        if (isExpirationUpdate && kind == MessageKind.TEXT) {
            kind = MessageKind.SYSTEM
            payload.put("expirationUpdate", true)
            payload.put("expireTimer", expire)
        }

        val payloadJson = payload.takeIf { it.length() > 0 }?.toString()
        return Triple(kind, payloadJson, expire)
    }

    fun hasRichContent(dataMessage: DataMessage): Boolean {
        val body = dataMessage.body?.trim().orEmpty()
        return dataMessage.sticker != null ||
            dataMessage.pollCreate != null ||
            dataMessage.contact.isNotEmpty() ||
            dataMessage.reaction != null ||
            dataMessage.quote != null ||
            body.startsWith("geo:", ignoreCase = true) ||
            (dataMessage.expireTimer?.let { it > 0 } == true) ||
            ((dataMessage.flags ?: 0) != 0)
    }
}
