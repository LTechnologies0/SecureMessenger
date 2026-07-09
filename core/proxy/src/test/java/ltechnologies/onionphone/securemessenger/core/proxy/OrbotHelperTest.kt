package ltechnologies.onionphone.securemessenger.core.proxy

import org.junit.Assert.assertEquals
import org.junit.Test

class OrbotHelperTest {

    @Test
    fun resolveSocksPort_prefersExplicitPortExtra() {
        assertEquals(9150, OrbotHelper.resolveSocksPort(9150, -1, null))
    }

    @Test
    fun resolveSocksPort_fallsBackToSocksUrl() {
        assertEquals(9060, OrbotHelper.resolveSocksPort(-1, -1, "socks://127.0.0.1:9060"))
    }

    @Test
    fun resolveSocksPort_defaultsTo9050() {
        assertEquals(OrbotConstants.SOCKS_PORT, OrbotHelper.resolveSocksPort(-1, -1, null))
    }
}
