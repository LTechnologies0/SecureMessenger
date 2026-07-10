package ltechnologies.onionphone.securemessenger.protocol.matrix

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class MatrixRoomTitlesTest {

    @Test
    fun resolve_usesExplicitRoomName() {
        val title = MatrixRoomTitles.resolve(
            roomId = "!abc:server",
            stateEvents = listOf(
                member("@alice:s", "@bob:s", "Bob"),
                roomName("Community Lounge"),
            ),
            userId = "@alice:s",
        )
        assertEquals("Community Lounge", title)
    }

    @Test
    fun resolve_dmUsesPeerMxidWhenNoDisplayName() {
        val title = MatrixRoomTitles.resolve(
            roomId = "!abc:server",
            stateEvents = listOf(
                member("@alice:example.org", "@alice:example.org", "alice"),
                member("@alice:example.org", "@bob:other.example", display = null),
            ),
            userId = "@alice:example.org",
        )
        assertEquals("@bob:other.example", title)
    }

    @Test
    fun resolve_dmUsesDisplayNameWhenPresent() {
        val title = MatrixRoomTitles.resolve(
            roomId = "!abc:server",
            stateEvents = listOf(
                member("@alice:s", "@bob:s", "Bob The Builder"),
            ),
            userId = "@alice:s",
        )
        assertEquals("Bob The Builder", title)
    }

    @Test
    fun resolve_dmUsesMessageSenderWhenMemberStateMissing() {
        val title = MatrixRoomTitles.resolve(
            roomId = "!roomid:example.org",
            stateEvents = listOf(
                RoomEvent(type = "m.room.message", sender = "@bob:other.example"),
            ),
            userId = "@alice:example.org",
        )
        assertEquals("@bob:other.example", title)
    }

    @Test
    fun resolve_unknownRoomIdAvoidsOpaqueBangId() {
        val title = MatrixRoomTitles.resolve(
            roomId = "!opaqueRoomId:example.org",
            stateEvents = emptyList(),
            userId = "@alice:s",
        )
        assertEquals("Conversation Matrix", title)
    }

    private fun roomName(name: String) = RoomEvent(
        type = "m.room.name",
        content = JsonObject(mapOf("name" to JsonPrimitive(name))),
    )

    private fun member(sender: String, stateKey: String, display: String?, membership: String = "join") =
        RoomEvent(
            type = "m.room.member",
            sender = sender,
            stateKey = stateKey,
            content = JsonObject(
                buildMap {
                    put("membership", JsonPrimitive(membership))
                    if (display != null) put("displayname", JsonPrimitive(display))
                },
            ),
        )
}
