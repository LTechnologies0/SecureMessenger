package ltechnologies.onionphone.securemessenger.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TorSocksOkHttpTest {
    @Test
    fun socksRemoteDns_preservesHostnameForSni() {
        val addrs = TorSocksOkHttp.socksRemoteDns.lookup("matrix.example.org")
        assertEquals(1, addrs.size)
        assertEquals("matrix.example.org", addrs[0].hostName)
        assertTrue(addrs[0].address.contentEquals(byteArrayOf(0, 0, 0, 1)))
    }

    @Test
    fun socksRemoteDns_literalIpv4() {
        val addrs = TorSocksOkHttp.socksRemoteDns.lookup("1.2.3.4")
        assertEquals("1.2.3.4", addrs.single().hostName)
        assertTrue(addrs.single().address.contentEquals(byteArrayOf(1, 2, 3, 4)))
    }
}
