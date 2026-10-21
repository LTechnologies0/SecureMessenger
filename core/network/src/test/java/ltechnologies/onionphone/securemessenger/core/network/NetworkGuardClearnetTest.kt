package ltechnologies.onionphone.securemessenger.core.network

import ltechnologies.onionphone.securemessenger.core.proxy.evaluateNetworkAllowed
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** NetworkGuard refuses clearnet: SOCKS must be healthy regardless of stored torRequired. */
class NetworkGuardClearnetTest {

    @Test
    fun refusesClearnetWhenSocksDown() {
        assertFalse(evaluateNetworkAllowed(torRequired = false, proxyHealthy = false))
        assertFalse(evaluateNetworkAllowed(torRequired = true, proxyHealthy = false))
    }

    @Test
    fun allowsOnlyHealthySocks() {
        assertTrue(evaluateNetworkAllowed(torRequired = true, proxyHealthy = true))
    }
}
