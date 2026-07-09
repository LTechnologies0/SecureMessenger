package ltechnologies.onionphone.securemessenger.protocol.matrix

import org.junit.Assert.assertEquals
import org.junit.Test

class MatrixUrlsTest {

    @Test
    fun normalizeHomeserver_addsHttpsScheme() {
        assertEquals("https://matrix.org", MatrixUrls.normalizeHomeserver("matrix.org"))
        assertEquals("https://matrix.org", MatrixUrls.normalizeHomeserver("https://matrix.org/"))
    }

    @Test
    fun loginLocalPart_stripsMxidPrefix() {
        assertEquals("alice", MatrixUrls.loginLocalPart("@alice:matrix.org"))
    }

    @Test
    fun normalizeUserId_buildsMxidFromLocalPart() {
        assertEquals(
            "@alice:matrix.org",
            MatrixUrls.normalizeUserId("alice", "https://matrix.org"),
        )
    }
}
