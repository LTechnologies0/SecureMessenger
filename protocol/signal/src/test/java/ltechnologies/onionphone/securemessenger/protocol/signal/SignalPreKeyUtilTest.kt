package ltechnologies.onionphone.securemessenger.protocol.signal

import org.junit.Assert.assertNotNull
import org.junit.Test

class SignalPreKeyUtilTest {
    @Test
    fun generatePreKeyMaterial() {
        val material = SignalPreKeyMaterial.generate()
        assertNotNull(material.aciPreKeys)
        assertNotNull(material.pniPreKeys)
        assert(material.aciRegistrationId != 0)
        assert(material.pniRegistrationId != 0)
    }
}
