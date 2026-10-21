package ltechnologies.onionphone.securemessenger.core.proxy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkPolicyTest {

    @Test
    fun refusesClearnetWhenProxyUnhealthy() {
        assertFalse(evaluateNetworkAllowed(torRequired = true, proxyHealthy = false))
        // Legacy stored torRequired=false is not a clearnet escape hatch.
        assertFalse(evaluateNetworkAllowed(torRequired = false, proxyHealthy = false))
    }

    @Test
    fun allowsOnlyWhenProxyHealthy() {
        assertTrue(evaluateNetworkAllowed(torRequired = true, proxyHealthy = true))
        assertTrue(evaluateNetworkAllowed(torRequired = false, proxyHealthy = true))
    }
}
