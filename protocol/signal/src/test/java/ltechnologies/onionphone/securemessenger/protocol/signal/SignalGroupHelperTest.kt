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

    @Test
    fun inviteLink_roundTripsMasterKeyAndPassword() {
        val masterKey = ByteArray(32) { (it + 3).toByte() }
        val password = ByteArray(16) { (it + 9).toByte() }
        val url = SignalGroupHelper.encodeInviteLinkUrl(masterKey, password)
        assertTrue(url.startsWith("https://signal.group/#"))
        val parsed = SignalGroupHelper.parseInviteLink(url)
        assertArrayEquals(masterKey, parsed!!.masterKeyBytes)
        assertArrayEquals(password, parsed.password)
    }

    @Test
    fun inviteLink_parsesFragmentOnlyAndLegacyHost() {
        val masterKey = ByteArray(32) { 1 }
        val password = ByteArray(16) { 2 }
        val url = SignalGroupHelper.encodeInviteLinkUrl(masterKey, password)
        val fragment = url.substringAfter('#')
        assertArrayEquals(masterKey, SignalGroupHelper.parseInviteLink(fragment)!!.masterKeyBytes)
        val legacy = "https://group.signal.org/#$fragment"
        assertArrayEquals(password, SignalGroupHelper.parseInviteLink(legacy)!!.password)
    }

    @Test
    fun inviteLink_rejectsForeignHost() {
        val masterKey = ByteArray(32) { 1 }
        val password = ByteArray(16) { 2 }
        val fragment = SignalGroupHelper.encodeInviteLinkUrl(masterKey, password).substringAfter('#')
        assertNull(SignalGroupHelper.parseInviteLink("https://evil.example/#$fragment"))
    }
}
