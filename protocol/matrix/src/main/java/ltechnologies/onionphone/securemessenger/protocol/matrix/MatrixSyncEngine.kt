package ltechnologies.onionphone.securemessenger.protocol.matrix

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import ltechnologies.onionphone.securemessenger.core.model.Conversation
import ltechnologies.onionphone.securemessenger.core.model.DeliveryState
import ltechnologies.onionphone.securemessenger.core.model.Message
import ltechnologies.onionphone.securemessenger.core.model.MessageDirection
import ltechnologies.onionphone.securemessenger.core.model.ProtocolId
import ltechnologies.onionphone.securemessenger.data.MessengerRepository
import timber.log.Timber

/**
 * Matrix Client-Server `/sync` loop (CS API § Client-Server API /sync).
 */
class MatrixSyncEngine(
    private val repository: MessengerRepository,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun start(
        accountId: String,
        userId: String,
        homeserver: String,
        accessToken: String,
        client: HttpClient,
        since: String? = null,
        onSinceUpdated: (String) -> Unit = {},
        onAuthExpired: () -> Unit = {},
    ) {
        stop()
        job = scope.launch {
            var nextBatch: String? = since
            while (isActive) {
                try {
                    val url = "${homeserver.trimEnd('/')}/_matrix/client/v3/sync"
                    val response = client.get(url) {
                        header("Authorization", "Bearer $accessToken")
                        nextBatch?.let { parameter("since", it) }
                        parameter("timeout", 30_000)
                    }
                    if (response.status == HttpStatusCode.Unauthorized) {
                        Timber.w("Matrix sync token expired")
                        onAuthExpired()
                        break
                    }
                    val body: SyncResponse = response.body()
                    body.nextBatch?.let { batch ->
                        nextBatch = batch
                        onSinceUpdated(batch)
                    }
                    processRooms(accountId, userId, body)
                } catch (e: Exception) {
                    Timber.w(e, "Matrix sync error")
                    delay(5_000)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun processRooms(accountId: String, userId: String, sync: SyncResponse) {
        val rooms = sync.rooms?.join ?: return
        val conversations = mutableListOf<Conversation>()
        val messages = mutableListOf<Message>()

        rooms.forEach { (roomId, roomData) ->
            val convId = MatrixProtocol.conversationIdFor(accountId, roomId)
            val title = roomData.state?.events?.firstOrNull { it.type == "m.room.name" }
                ?.content?.get("name")?.jsonPrimitive?.content
                ?: roomId

            var lastPreview: String? = null
            var lastAt = 0L

            roomData.timeline?.events?.forEach { event ->
                when (event.type) {
                    "m.room.message" -> {
                        val body = event.content?.get("body")?.jsonPrimitive?.content ?: return@forEach
                        val ts = event.originServerTs ?: System.currentTimeMillis()
                        val direction = if (event.sender == userId) {
                            MessageDirection.OUTGOING
                        } else {
                            MessageDirection.INCOMING
                        }
                        messages.add(
                            Message(
                                id = event.eventId ?: "${convId}_$ts",
                                conversationId = convId,
                                protocol = ProtocolId.MATRIX,
                                body = body,
                                timestamp = ts,
                                direction = direction,
                                deliveryState = DeliveryState.DELIVERED,
                                senderDisplayName = event.sender,
                            ),
                        )
                        lastPreview = body.take(100)
                        lastAt = ts
                    }
                    "m.room.encrypted" -> {
                        // Megolm ciphertext — requires crypto store (Trixnity path).
                        Timber.d("Skipping encrypted event in HTTP sync for $roomId")
                    }
                }
            }

            conversations.add(
                Conversation(
                    id = convId,
                    protocol = ProtocolId.MATRIX,
                    accountId = accountId,
                    remoteId = roomId,
                    title = title,
                    lastMessagePreview = lastPreview,
                    lastMessageAt = lastAt,
                ),
            )
        }

        if (conversations.isNotEmpty()) repository.upsertConversations(conversations)
        if (messages.isNotEmpty()) repository.upsertMessages(messages)
    }
}

@Serializable
data class SyncResponse(
    @SerialName("next_batch") val nextBatch: String? = null,
    val rooms: RoomsSection? = null,
)

@Serializable
data class RoomsSection(
    val join: Map<String, JoinedRoom>? = null,
)

@Serializable
data class JoinedRoom(
    val state: StateSection? = null,
    val timeline: TimelineSection? = null,
)

@Serializable
data class StateSection(
    val events: List<RoomEvent>? = null,
)

@Serializable
data class TimelineSection(
    val events: List<RoomEvent>? = null,
)

@Serializable
data class RoomEvent(
    val type: String? = null,
    val sender: String? = null,
    @SerialName("event_id") val eventId: String? = null,
    @SerialName("origin_server_ts") val originServerTs: Long? = null,
    val content: JsonObject? = null,
)
