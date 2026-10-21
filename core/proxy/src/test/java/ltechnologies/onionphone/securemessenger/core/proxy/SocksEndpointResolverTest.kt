package ltechnologies.onionphone.securemessenger.core.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SocksEndpointResolverTest {

    @Test
    fun resolveReachableHost_keepsCustomHost() {
        assertEquals("10.0.0.1", SocksEndpointResolver.resolveReachableHost("10.0.0.1", 9050))
    }

    @Test
    fun resolveReachableHost_loopbackOnly_noGatewayOrEmulatorAlias() {
        assertEquals("127.0.0.1", SocksEndpointResolver.resolveReachableHost("127.0.0.1", 9050))
        assertEquals("127.0.0.1", SocksEndpointResolver.resolveReachableHost("localhost", 9050))
        assertEquals("127.0.0.1", SocksEndpointResolver.resolveReachableHost("::1", 18202))
    }

    @Test
    fun isHostBridgeEndpoint_loopbackOnly() {
        assertTrue(SocksEndpointResolver.isHostBridgeEndpoint("127.0.0.1"))
        assertTrue(SocksEndpointResolver.isHostBridgeEndpoint("localhost"))
        assertFalse(SocksEndpointResolver.isHostBridgeEndpoint("10.0.2.2"))
        assertFalse(SocksEndpointResolver.isHostBridgeEndpoint("192.168.240.1"))
        assertFalse(SocksEndpointResolver.isHostBridgeEndpoint("10.0.0.1"))
    }
}
