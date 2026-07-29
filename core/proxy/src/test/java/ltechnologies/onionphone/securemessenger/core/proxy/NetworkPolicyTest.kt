package ltechnologies.onionphone.securemessenger.core.proxy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkPolicyTest {

    @Test
    fun blocksOnlyWhenTorRequiredAndProxyUnhealthy() {
        assertFalse(evaluateNetworkAllowed(torRequired = true, proxyHealthy = false))
        assertTrue(evaluateNetworkAllowed(torRequired = false, proxyHealthy = false))
    }

    @Test
    fun allowsWhenProxyHealthyOrTorOptional() {
        assertTrue(evaluateNetworkAllowed(torRequired = true, proxyHealthy = true))
        assertTrue(evaluateNetworkAllowed(torRequired = false, proxyHealthy = true))
        assertTrue(evaluateNetworkAllowed(torRequired = false, proxyHealthy = false))
    }
}
