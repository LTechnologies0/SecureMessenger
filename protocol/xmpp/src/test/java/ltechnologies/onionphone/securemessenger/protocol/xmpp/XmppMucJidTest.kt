package ltechnologies.onionphone.securemessenger.protocol.xmpp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XmppMucJidTest {
    @Test
    fun detectsConferenceJids() {
        assertTrue(SmackClientFacade.isLikelyMucJid("room@conference.example.org"))
        assertTrue(SmackClientFacade.isLikelyMucJid("room@muc.example.org"))
        assertFalse(SmackClientFacade.isLikelyMucJid("alice@example.org"))
    }
}
