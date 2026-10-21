package ltechnologies.onionphone.securemessenger.protocol.signal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SignalPreKeyUtilTest {
    @Test
    fun generatePreKeyMaterial() {
        val material = SignalPreKeyMaterial.generate()
        assertNotNull(material.aciPreKeys)
        assertNotNull(material.pniPreKeys)
        assert(material.aciRegistrationId != 0)
        assert(material.pniRegistrationId != 0)
        assertEquals(SignalPreKeyMaterial.ONE_TIME_PREKEY_BATCH, material.aciOneTimeEcPreKeys.size)
        assertEquals(SignalPreKeyMaterial.ONE_TIME_PREKEY_BATCH, material.pniOneTimeEcPreKeys.size)
        assertEquals(SignalPreKeyMaterial.ONE_TIME_PREKEY_BATCH, material.aciOneTimeKyberPreKeys.size)
        assertEquals(SignalPreKeyMaterial.ONE_TIME_PREKEY_BATCH, material.pniOneTimeKyberPreKeys.size)
        assertEquals(
            SignalPreKeyMaterial.LAST_RESORT_KYBER_PREKEY_ID,
            material.aciPreKeys.lastResortKyberPreKey.id,
        )
        assert(material.aciOneTimeKyberPreKeys.none { it.id == material.aciPreKeys.lastResortKyberPreKey.id })
    }

    @Test
    fun prekeyReplenishmentThresholdMatchesSignalAndroid() {
        assertEquals(10, SignalPreKeyMaterial.ONE_TIME_PREKEY_MINIMUM)
    }

    @Test
    fun registrationDoesNotSendSha256Pin() {
        val material = SignalPreKeyMaterial.generate()
        val attributes = material.buildAccountAttributes("password", "1234")
        assertNull(attributes.registrationLock)
        assertNull(attributes.recoveryPassword)
    }
}
