package ltechnologies.onionphone.securemessenger.protocol.matrix

import org.junit.Assert.assertEquals
import org.junit.Test

class MatrixPayloadJsonTest {

    @Test
    fun mergeReactionEmoji_dedupes() {
        assertEquals(listOf("👍"), MatrixPayloadJson.mergeReactionEmoji(emptyList(), "👍"))
        assertEquals(
            listOf("👍", "❤️"),
            MatrixPayloadJson.mergeReactionEmoji(listOf("👍"), "❤️"),
        )
        assertEquals(
            listOf("👍"),
            MatrixPayloadJson.mergeReactionEmoji(listOf("👍"), "👍"),
        )
    }

    @Test
    fun mergeReactionEmoji_ignoresBlank() {
        assertEquals(listOf("👍"), MatrixPayloadJson.mergeReactionEmoji(listOf("👍"), "  "))
        assertEquals(emptyList<String>(), MatrixPayloadJson.mergeReactionEmoji(emptyList(), ""))
    }
}
