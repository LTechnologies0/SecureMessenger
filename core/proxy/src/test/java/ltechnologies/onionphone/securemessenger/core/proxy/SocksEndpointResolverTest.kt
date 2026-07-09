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
    fun isHostBridgeEndpoint_recognizesLoopbackAndEmulator() {
        assertTrue(SocksEndpointResolver.isHostBridgeEndpoint("127.0.0.1"))
        assertTrue(SocksEndpointResolver.isHostBridgeEndpoint("localhost"))
        assertTrue(SocksEndpointResolver.isHostBridgeEndpoint("10.0.2.2"))
        assertTrue(SocksEndpointResolver.isHostBridgeEndpoint("192.168.240.1"))
        assertFalse(SocksEndpointResolver.isHostBridgeEndpoint("10.0.0.1"))
    }
}
