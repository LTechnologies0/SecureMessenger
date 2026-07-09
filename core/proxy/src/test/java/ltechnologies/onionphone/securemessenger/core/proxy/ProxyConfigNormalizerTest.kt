package ltechnologies.onionphone.securemessenger.core.proxy

import ltechnologies.onionphone.securemessenger.core.model.TorProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class ProxyConfigNormalizerTest {

    @Test
    fun normalizeHost_mapsLocalhostToLoopbackIpv4() {
        assertEquals("127.0.0.1", ProxyConfigNormalizer.normalizeHost("localhost"))
        assertEquals("127.0.0.1", ProxyConfigNormalizer.normalizeHost("::1"))
    }

    @Test
    fun configForSave_customUsesEditedFields() {
        val saved = ProxyConfigNormalizer.normalize(
            ltechnologies.onionphone.securemessenger.core.model.ProxyConfig(
                host = "10.0.0.1",
                port = 1080,
                torProvider = TorProvider.CUSTOM,
            ),
        )
        val result = ProxyConfigNormalizer.configForSave(
            torProvider = TorProvider.CUSTOM,
            customHost = "localhost",
            customPort = 9150,
            resolvedStatus = saved,
            username = "user",
            password = "secret",
        )
        assertEquals("127.0.0.1", result.host)
        assertEquals(9150, result.port)
        assertEquals("user", result.username)
        assertEquals("secret", result.password)
    }
}
