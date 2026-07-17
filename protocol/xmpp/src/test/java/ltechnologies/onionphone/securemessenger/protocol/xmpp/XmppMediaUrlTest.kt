package ltechnologies.onionphone.securemessenger.protocol.xmpp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XmppMediaUrlTest {
    @Test
    fun extractHttpUploadUrl_acceptsHttpsAndAesgcm() {
        assertEquals(
            "https://upload.example/file.bin",
            SmackClientFacade.extractHttpUploadUrl("https://upload.example/file.bin"),
        )
        assertEquals(
            "aesgcm://upload.example/file.bin#deadbeef",
            SmackClientFacade.extractHttpUploadUrl("aesgcm://upload.example/file.bin#deadbeef"),
        )
    }

    @Test
    fun extractHttpUploadUrl_picksUrlFromMultilineBody() {
        val body = "Voici le fichier\nhttps://upload.example/a.png\nmerci"
        assertEquals("https://upload.example/a.png", SmackClientFacade.extractHttpUploadUrl(body))
    }

    @Test
    fun extractHttpUploadUrl_rejectsPlainText() {
        assertNull(SmackClientFacade.extractHttpUploadUrl("hello world"))
    }
}
