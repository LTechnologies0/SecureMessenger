package ltechnologies.onionphone.securemessenger.core.proxy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkPolicyTest {

    @Test
    fun blocksWhenProxyUnhealthy() {
        assertFalse(evaluateNetworkAllowed(torRequired = true, proxyHealthy = false))
        assertFalse(evaluateNetworkAllowed(torRequired = false, proxyHealthy = false))
    }

    @Test
    fun allowsWhenProxyHealthy() {
        assertTrue(evaluateNetworkAllowed(torRequired = true, proxyHealthy = true))
        assertTrue(evaluateNetworkAllowed(torRequired = false, proxyHealthy = true))
    }
}
