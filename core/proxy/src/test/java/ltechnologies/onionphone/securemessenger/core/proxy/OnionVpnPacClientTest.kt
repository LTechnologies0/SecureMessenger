package ltechnologies.onionphone.securemessenger.core.proxy

import ltechnologies.onionphone.securemessenger.core.model.TorProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OnionVpnPacClientTest {

    @Test
    fun parseSocksFromPac_readsSocks5Line() {
        val pac = """
            function FindProxyForURL(url, host) {
              return "SOCKS5 127.0.0.1:18202; SOCKS 127.0.0.1:18202";
            }
        """.trimIndent()
        val endpoint = OnionVpnPacClient.parseSocksFromPac(pac)
        assertNotNull(endpoint)
        assertEquals("127.0.0.1", endpoint!!.host)
        assertEquals(18202, endpoint.port)
        assertEquals(true, endpoint.fromPac)
    }

    @Test
    fun parseSocksFromPac_failClosedWithoutSocks_returnsNull() {
        val pac = """
            function FindProxyForURL(url, host) {
              return "PROXY 127.0.0.1:1";
            }
        """.trimIndent()
        assertNull(OnionVpnPacClient.parseSocksFromPac(pac))
    }

    @Test
    fun configForSave_onionVpnUsesPacBridgeDefaults() {
        val result = ProxyConfigNormalizer.configForSave(
            torProvider = TorProvider.ONIONVPN,
            customHost = "ignored",
            customPort = 1,
            resolvedStatus = ProxyConfigNormalizer.normalize(
                ltechnologies.onionphone.securemessenger.core.model.ProxyConfig(
                    host = "10.0.0.1",
                    port = 1080,
                ),
            ),
            torRequired = true,
        )
        assertEquals(OnionVpnConstants.DEFAULT_BRIDGE_HOST, result.host)
        assertEquals(OnionVpnConstants.DEFAULT_BRIDGE_PORT, result.port)
        assertEquals(true, result.torRequired)
        assertEquals(TorProvider.ONIONVPN, result.torProvider)
        assertNull(result.username)
        assertNull(result.password)
    }
}
