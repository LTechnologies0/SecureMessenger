package ltechnologies.onionphone.securemessenger.protocol.email

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import ltechnologies.onionphone.securemessenger.core.model.SendResult
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber

/**
 * Minimal JMAP (RFC 8620/8621) client: session discovery, Email/query|get, EmailSubmission/set.
 */
class JmapClient(
    private val http: OkHttpClient,
    private val sessionUrl: String,
    private val email: String,
    private val password: String,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private val jsonMedia = "application/json".toMediaType()

    @Volatile
    private var session: JmapSessionState? = null

    fun isConnected(): Boolean = session != null

    fun close() {
        session = null
    }

    fun connect() {
        val request = Request.Builder()
            .url(sessionUrl)
            .header("Authorization", Credentials.basic(email, password))
            .header("Accept", "application/json")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("JMAP session HTTP ${response.code}")
            }
            val body = response.body.string()
            val parsed = json.decodeFromString(JmapSessionResponse.serializer(), body)
            val accountId = parsed.primaryAccounts["urn:ietf:params:jmap:mail"]
                ?: parsed.accounts.keys.firstOrNull()
                ?: error("No JMAP mail account")
            val apiUrl = parsed.apiUrl ?: error("Missing JMAP apiUrl")
            val state = JmapSessionState(apiUrl = apiUrl, accountId = accountId)
            session = state
            resolveRoles(state)
        }
    }

    fun listRecentEmailIds(limit: Int = 50): List<String> {
        val s = session ?: error("Not connected")
        val mailboxId = s.inboxId ?: return emptyList()
        val call = jmapEnvelope(
            method = "Email/query",
            args = buildJsonObject {
                put("accountId", s.accountId)
                put("filter", buildJsonObject { put("inMailbox", mailboxId) })
                put(
                    "sort",
                    buildJsonArray {
                        add(buildJsonObject {
                            put("property", "receivedAt")
                            put("isAscending", false)
                        })
                    },
                )
                put("limit", limit)
            },
            usingSubmission = false,
        )
        val payload = firstMethodPayload(postApi(s.apiUrl, call)) ?: return emptyList()
        return payload["ids"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            .orEmpty()
    }

    fun getEmails(ids: List<String>): List<JmapEmail> {
        if (ids.isEmpty()) return emptyList()
        val s = session ?: error("Not connected")
        val call = jmapEnvelope(
            method = "Email/get",
            args = buildJsonObject {
                put("accountId", s.accountId)
                put("ids", buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } })
                put(
                    "properties",
                    buildJsonArray {
                        listOf(
                            "id", "threadId", "subject", "from", "to", "cc", "messageId",
                            "inReplyTo", "references", "receivedAt", "sentAt", "preview",
                            "bodyValues", "textBody",
                        ).forEach { add(JsonPrimitive(it)) }
                    },
                )
                put("fetchTextBodyValues", true)
                put("maxBodyValueBytes", 1_000_000)
            },
            usingSubmission = false,
        )
        val payload = firstMethodPayload(postApi(s.apiUrl, call)) ?: return emptyList()
        val list = payload["list"]?.jsonArray ?: return emptyList()
        return list.mapNotNull { el ->
            runCatching { json.decodeFromJsonElement(JmapEmail.serializer(), el) }.getOrNull()
        }
    }

    fun submit(
        to: List<String>,
        subject: String,
        body: String,
        inReplyTo: String? = null,
    ): SendResult {
        val s = session ?: return SendResult.Failure("JMAP not connected")
        return try {
            val recipients = to.map { EmailAddress.requireValid(it) }
            val identityId = s.identityId
                ?: return SendResult.Failure("No JMAP identity — configure provider identity")
            val draftMailbox = s.draftsId ?: s.inboxId
                ?: return SendResult.Failure("No JMAP mailbox")
            val createId = "draft-1"
            val call = buildJsonObject {
                put(
                    "using",
                    buildJsonArray {
                        add(JsonPrimitive("urn:ietf:params:jmap:core"))
                        add(JsonPrimitive("urn:ietf:params:jmap:mail"))
                        add(JsonPrimitive("urn:ietf:params:jmap:submission"))
                    },
                )
                put(
                    "methodCalls",
                    buildJsonArray {
                        add(
                            buildJsonArray {
                                add(JsonPrimitive("Email/set"))
                                add(
                                    buildJsonObject {
                                        put("accountId", s.accountId)
                                        put(
                                            "create",
                                            buildJsonObject {
                                                put(
                                                    createId,
                                                    buildJsonObject {
                                                        put(
                                                            "mailboxIds",
                                                            buildJsonObject { put(draftMailbox, true) },
                                                        )
                                                        put(
                                                            "from",
                                                            buildJsonArray {
                                                                add(buildJsonObject { put("email", email) })
                                                            },
                                                        )
                                                        put(
                                                            "to",
                                                            buildJsonArray {
                                                                recipients.forEach { addr ->
                                                                    add(buildJsonObject { put("email", addr) })
                                                                }
                                                            },
                                                        )
                                                        put("subject", subject)
                                                        if (!inReplyTo.isNullOrBlank()) {
                                                            put(
                                                                "inReplyTo",
                                                                buildJsonArray { add(JsonPrimitive(inReplyTo)) },
                                                            )
                                                        }
                                                        put(
                                                            "bodyValues",
                                                            buildJsonObject {
                                                                put(
                                                                    "body",
                                                                    buildJsonObject {
                                                                        put("value", body)
                                                                        put("charset", "utf-8")
                                                                    },
                                                                )
                                                            },
                                                        )
                                                        put(
                                                            "textBody",
                                                            buildJsonArray {
                                                                add(
                                                                    buildJsonObject {
                                                                        put("partId", "body")
                                                                        put("type", "text/plain")
                                                                    },
                                                                )
                                                            },
                                                        )
                                                    },
                                                )
                                            },
                                        )
                                    },
                                )
                                add(JsonPrimitive("0"))
                            },
                        )
                        add(
                            buildJsonArray {
                                add(JsonPrimitive("EmailSubmission/set"))
                                add(
                                    buildJsonObject {
                                        put("accountId", s.accountId)
                                        put(
                                            "create",
                                            buildJsonObject {
                                                put(
                                                    "sub-1",
                                                    buildJsonObject {
                                                        put("identityId", identityId)
                                                        put("emailId", "#$createId")
                                                    },
                                                )
                                            },
                                        )
                                    },
                                )
                                add(JsonPrimitive("1"))
                            },
                        )
                    },
                )
            }
            postApi(s.apiUrl, call)
            SendResult.Success("jmap-${System.currentTimeMillis()}")
        } catch (e: Exception) {
            Timber.w(e, "JMAP submit failed")
            SendResult.Failure(e.message ?: "JMAP submit failed")
        }
    }

    private fun resolveRoles(state: JmapSessionState) {
        val call = jmapEnvelope(
            method = "Mailbox/query",
            args = buildJsonObject {
                put("accountId", state.accountId)
            },
            usingSubmission = false,
        )
        val queryPayload = firstMethodPayload(postApi(state.apiUrl, call)) ?: return
        val ids = queryPayload["ids"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            .orEmpty()
        if (ids.isEmpty()) return

        val getCall = jmapEnvelope(
            method = "Mailbox/get",
            args = buildJsonObject {
                put("accountId", state.accountId)
                put("ids", buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } })
                put(
                    "properties",
                    buildJsonArray {
                        listOf("id", "role", "name").forEach { add(JsonPrimitive(it)) }
                    },
                )
            },
            usingSubmission = false,
        )
        val getPayload = firstMethodPayload(postApi(state.apiUrl, getCall)) ?: return
        val list = getPayload["list"]?.jsonArray ?: return
        for (el in list) {
            val obj = el.jsonObject
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: continue
            val role = obj["role"]?.jsonPrimitive?.contentOrNull?.lowercase()
            when (role) {
                "inbox" -> state.inboxId = id
                "drafts" -> state.draftsId = id
            }
        }
        if (state.inboxId == null) {
            state.inboxId = list.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
        }

        val identityCall = jmapEnvelope(
            method = "Identity/get",
            args = buildJsonObject {
                put("accountId", state.accountId)
            },
            usingSubmission = true,
        )
        runCatching {
            val identityPayload = firstMethodPayload(postApi(state.apiUrl, identityCall))
            state.identityId = identityPayload?.get("list")?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("id")
                ?.jsonPrimitive
                ?.contentOrNull
        }
    }

    private fun jmapEnvelope(
        method: String,
        args: JsonObject,
        usingSubmission: Boolean,
    ): JsonObject = buildJsonObject {
        put(
            "using",
            buildJsonArray {
                add(JsonPrimitive("urn:ietf:params:jmap:core"))
                add(JsonPrimitive("urn:ietf:params:jmap:mail"))
                if (usingSubmission) {
                    add(JsonPrimitive("urn:ietf:params:jmap:submission"))
                }
            },
        )
        put(
            "methodCalls",
            buildJsonArray {
                add(
                    buildJsonArray {
                        add(JsonPrimitive(method))
                        add(args)
                        add(JsonPrimitive("0"))
                    },
                )
            },
        )
    }

    private fun firstMethodPayload(result: JsonObject): JsonObject? {
        val methodResponses = result["methodResponses"]?.jsonArray ?: return null
        val first = methodResponses.firstOrNull()?.jsonArray ?: return null
        val name = first.getOrNull(0)?.jsonPrimitive?.contentOrNull
        if (name != null && name.endsWith("/error", ignoreCase = true)) {
            val detail = first.getOrNull(1)?.toString()?.take(200)
            error("JMAP error: $detail")
        }
        return first.getOrNull(1)?.jsonObject
    }

    private fun postApi(apiUrl: String, body: JsonObject): JsonObject {
        val request = Request.Builder()
            .url(apiUrl)
            .header("Authorization", Credentials.basic(email, password))
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(jsonMedia))
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) {
                error("JMAP API HTTP ${response.code}: ${text.take(200)}")
            }
            return json.parseToJsonElement(text).jsonObject
        }
    }
}

private data class JmapSessionState(
    val apiUrl: String,
    val accountId: String,
    var inboxId: String? = null,
    var draftsId: String? = null,
    var identityId: String? = null,
)

@Serializable
data class JmapSessionResponse(
    val apiUrl: String? = null,
    val username: String? = null,
    val accounts: Map<String, JsonObject> = emptyMap(),
    val primaryAccounts: Map<String, String> = emptyMap(),
)

@Serializable
data class JmapEmail(
    val id: String,
    val threadId: String? = null,
    val subject: String? = null,
    val preview: String? = null,
    val from: List<JmapEmailAddress>? = null,
    val to: List<JmapEmailAddress>? = null,
    val cc: List<JmapEmailAddress>? = null,
    val messageId: List<String>? = null,
    val inReplyTo: List<String>? = null,
    val references: List<String>? = null,
    val receivedAt: String? = null,
    val sentAt: String? = null,
    val bodyValues: Map<String, JmapBodyValue>? = null,
)

@Serializable
data class JmapEmailAddress(
    val name: String? = null,
    val email: String? = null,
)

@Serializable
data class JmapBodyValue(
    val value: String? = null,
    val isEncodingProblem: Boolean = false,
    val isTruncated: Boolean = false,
)
