package ltechnologies.onionphone.securemessenger.protocol.irc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IrcSlashCommandsTest {
    @Test
    fun escapeRoundTrip() {
        val raw = "a;b c\\d\n"
        assertEquals(raw, IrcSlashCommands.unescapeTag(IrcSlashCommands.escapeTag(raw)))
    }

    @Test
    fun escapeSpecials() {
        assertEquals("a\\:b\\sc\\\\d", IrcSlashCommands.escapeTag("a;b c\\d"))
    }
}

class IrcChannelKeysTest {
    @Test
    fun parseKeyedChannels() {
        val joins = IrcTargets.parseChannelJoins("#foo,#bar=secret, baz:key2")
        assertEquals(3, joins.size)
        assertEquals("#foo", joins[0].name)
        assertNull(joins[0].key)
        assertEquals("#bar", joins[1].name)
        assertEquals("secret", joins[1].key)
        assertEquals("#baz", joins[2].name)
        assertEquals("key2", joins[2].key)
    }

    @Test
    fun mergeChannelKeys() {
        val merged = IrcTargets.mergeChannelKeys("#a,#b", "#b=sekrit,#c=other")
        val by = merged.associateBy { it.name }
        assertEquals(null, by["#a"]?.key)
        assertEquals("sekrit", by["#b"]?.key)
        assertEquals("other", by["#c"]?.key)
    }

    @Test
    fun parseChannelsStillWorks() {
        assertEquals(listOf("#foo", "#bar", "#baz"), IrcTargets.parseChannels("#foo, bar;baz"))
    }
}
