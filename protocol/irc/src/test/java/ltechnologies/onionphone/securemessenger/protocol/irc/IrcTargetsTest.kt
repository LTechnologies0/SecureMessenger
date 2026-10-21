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
        assertTrue(IrcTargets.isChannel("+secret"))
        assertFalse(IrcTargets.isChannel("alice"))
        assertFalse(IrcTargets.isChannel("#"))
        assertFalse(IrcTargets.isChannel("#has space"))
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
        assertEquals("{alice}", IrcTargets.foldNick("[Alice]"))
        assertTrue(IrcTargets.sameNick("[Alice]", "{alice}"))
        assertEquals("~alice", IrcTargets.foldNick("^Alice"))
        assertEquals("#libera", IrcTargets.foldChannel("#LIBERA"))
        assertEquals("#{libera}", IrcTargets.foldChannel("#[LIBERA]"))
    }

    @Test
    fun conversationIdRoundTrip() {
        val id = IrcTargets.conversationId("acc-1", "#chan")
        assertEquals("acc-1", IrcTargets.accountIdFromConversation(id))
        assertEquals("#chan", IrcTargets.remoteFromConversation(id))
    }
}
