package ltechnologies.onionphone.securemessenger.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class TorProviderTest {

    @Test
    fun fromStored_mapsLegacyOrbotInvizibleToOnionVpn() {
        assertEquals(TorProvider.ONIONVPN, TorProvider.fromStored("ORBOT"))
        assertEquals(TorProvider.ONIONVPN, TorProvider.fromStored("INVIZIBLE"))
        assertEquals(TorProvider.ONIONVPN, TorProvider.fromStored("orbot"))
    }

    @Test
    fun fromStored_keepsOnionVpnAndCustom() {
        assertEquals(TorProvider.ONIONVPN, TorProvider.fromStored("ONIONVPN"))
        assertEquals(TorProvider.CUSTOM, TorProvider.fromStored("CUSTOM"))
        assertEquals(TorProvider.ONIONVPN, TorProvider.fromStored(null))
        assertEquals(TorProvider.ONIONVPN, TorProvider.fromStored("unknown"))
    }
}
