package ltechnologies.onionphone.securemessenger.protocol.irc

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.net.ssl.X509TrustManager

class IrcTlsTest {
    @Test
    fun systemTrustManagerFactory_hasX509Managers() {
        val tmf = IrcTls.systemTrustManagerFactory()
        val managers = tmf.trustManagers
        assertTrue(managers.isNotEmpty())
        assertNotNull(managers.firstOrNull { it is X509TrustManager })
    }
}
