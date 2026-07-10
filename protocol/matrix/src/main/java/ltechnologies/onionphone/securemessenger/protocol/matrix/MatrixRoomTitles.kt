package ltechnologies.onionphone.securemessenger.protocol.matrix

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Human-readable Matrix room titles for conversation lists (DMs, named rooms). */
internal object MatrixRoomTitles {
    fun resolve(roomId: String, stateEvents: List<RoomEvent>, userId: String): String {
        val events = stateEvents.ifEmpty { emptyList() }

        events.firstRoomName()?.let { return it }

        val peerLabel = events.joinedPeerLabel(userId)
        if (peerLabel != null) return peerLabel

        events.messagePeerMxid(userId)?.let { return it }

        return if (roomId.startsWith("!")) "Conversation Matrix" else roomId
    }

    private fun List<RoomEvent>.messagePeerMxid(userId: String): String? =
        asSequence()
            .filter { it.type == "m.room.message" }
            .mapNotNull { it.sender }
            .firstOrNull { it != userId }

    private fun List<RoomEvent>.firstRoomName(): String? =
        firstOrNull { it.type == "m.room.name" }
            ?.content
            ?.stringField("name")
            ?.takeIf { it.isNotBlank() }

    private fun List<RoomEvent>.joinedPeerLabel(userId: String): String? {
        val peers = filter { it.type == "m.room.member" }
            .mapNotNull { event ->
                val memberId = event.stateKey ?: return@mapNotNull null
                if (memberId == userId) return@mapNotNull null
                if (event.content?.stringField("membership") != "join") return@mapNotNull null
                event.content.stringField("displayname")?.takeIf { it.isNotBlank() } ?: memberId
            }
            .distinct()
        return peers.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    private fun JsonObject.stringField(key: String): String? =
        get(key)?.jsonPrimitive?.content
}
