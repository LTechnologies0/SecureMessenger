package ltechnologies.onionphone.securemessenger.protocol.signal

import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SignalGroupHelperTest {
    @Test
    fun parseMasterKey_decodesBase64Payload() {
        val raw = ByteArray(32) { it.toByte() }
        val encoded = Base64.getEncoder().encodeToString(raw)
        val parsed = SignalGroupHelper.parseMasterKey("gv2:$encoded")
        assertArrayEquals(raw, parsed)
    }

    @Test
    fun parseMasterKey_rejectsNonGroupRemoteId() {
        assertNull(SignalGroupHelper.parseMasterKey("+33600000000"))
        assertNull(SignalGroupHelper.parseMasterKey("not-a-group"))
    }
}
