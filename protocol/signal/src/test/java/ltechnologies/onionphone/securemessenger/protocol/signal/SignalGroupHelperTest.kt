package ltechnologies.onionphone.securemessenger.protocol.signal

import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.signal.libsignal.zkgroup.profiles.ProfileKey
import org.whispersystems.signalservice.api.crypto.UnidentifiedAccess

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

    @Test
    fun profileAccessKeyIsNotAZeroStoryKey() {
        val profileKey = ProfileKey(ByteArray(32) { 7 })
        val accessKey = UnidentifiedAccess.deriveAccessKeyFrom(profileKey)
        assertEquals(16, accessKey.size)
        assertFalse(accessKey.contentEquals(ByteArray(16)))
        assertTrue(accessKey.any { it != 0.toByte() })
    }
}
