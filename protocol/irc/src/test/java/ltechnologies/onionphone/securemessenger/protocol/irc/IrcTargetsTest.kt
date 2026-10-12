package ltechnologies.onionphone.securemessenger.protocol.irc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IrcTargetsTest {
    @Test
    fun channelDetection() {
        assertTrue(IrcTargets.isChannel("#libera"))
        assertTrue(IrcTargets.isChannel("&local"))
        assertFalse(IrcTargets.isChannel("alice"))
    }

    @Test
    fun parseChannels() {
        assertEquals(listOf("#foo", "#bar", "#baz"), IrcTargets.parseChannels("#foo, bar;baz"))
        assertEquals(listOf("#libera"), IrcTargets.parseChannels("#LIBERA"))
    }

    @Test
    fun foldNickAndChannel() {
        assertEquals("alice", IrcTargets.foldNick("Alice"))
        assertTrue(IrcTargets.sameNick("Alice", "alice"))
        assertEquals("#libera", IrcTargets.foldChannel("#LIBERA"))
    }

    @Test
    fun conversationIdRoundTrip() {
        val id = IrcTargets.conversationId("acc-1", "#chan")
        assertEquals("acc-1", IrcTargets.accountIdFromConversation(id))
        assertEquals("#chan", IrcTargets.remoteFromConversation(id))
    }
}
