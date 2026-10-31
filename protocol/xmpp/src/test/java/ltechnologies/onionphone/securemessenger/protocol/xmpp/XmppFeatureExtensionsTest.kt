package ltechnologies.onionphone.securemessenger.protocol.xmpp

import org.jivesoftware.smack.packet.XmlEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XmppFeatureExtensionsTest {

    @Test
    fun reactionExtensionSerializesNamespaceAndId() {
        val ext = XmppReactionExtension(targetId = "msg-1", reactions = listOf("👍", "🔥"))
        val xml = ext.toXML(XmlEnvironment.EMPTY).toString()
        assertTrue(xml.contains("urn:xmpp:reactions:0"))
        assertTrue(xml.contains("""id="msg-1"""") || xml.contains("id='msg-1'"))
        assertTrue(xml.contains("👍"))
        assertTrue(xml.contains("🔥"))
        assertEquals("reactions", ext.elementName)
        assertEquals(XmppReactionExtension.NAMESPACE, ext.namespace)
    }

    @Test
    fun reactionExtensionSkipsBlankEmojisInXml() {
        val ext = XmppReactionExtension(targetId = "a", reactions = listOf(" ", "👋"))
        val xml = ext.toXML(XmlEnvironment.EMPTY).toString()
        assertTrue(xml.contains("👋"))
        assertFalse(xml.contains("<reaction> </reaction>"))
    }

    @Test
    fun mucJidHeuristicsStillHold() {
        assertTrue(SmackClientFacade.isLikelyMucJid("room@conference.example.org"))
        assertFalse(SmackClientFacade.isLikelyMucJid("alice@example.org"))
    }
}
