package com.shilapi.xcertplay.network

import android.content.Context
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.InetAddress

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30])
class GalaxyHotspotAddressMemoryTest {
    private val context = RuntimeEnvironment.getApplication()
    private fun info(ssid: String = "private-test-ap", iface: String = "wlan1",
                     security: Iap2WirelessSecurity = Iap2WirelessSecurity.WPA_WPA2) =
        WirelessHotspotInfo(ssid, "private-test-password", security, 0, null, null, iface,
            InetAddress.getByName("192.168.43.1"), "Auto", WirelessHotspotBackend.MANUAL_HOTSPOT)

    @Test fun recreatedPolicyKeepsTheSameNetworkPreferenceWithoutStoringCredentials() {
        val memory = GalaxyHotspotAddressMemory(context, info())
        memory.remember("IPv6")
        assertEquals("IPv6", GalaxyHotspotAddressMemory(context, info()).preferredFamily())
        val stored = context.getSharedPreferences("galaxy_hotspot_address", Context.MODE_PRIVATE).all
        assertEquals(setOf("network", "family"), stored.keys)
        assertFalse(stored.toString().contains("private-test"))
        memory.remember("invalid")
        assertEquals("IPv6", memory.preferredFamily())
    }

    @Test fun otherNetworkInterfaceAndSecurityCannotReuseASuccessfulFamily() {
        GalaxyHotspotAddressMemory(context, info()).remember("IPv6")
        assertNull(GalaxyHotspotAddressMemory(context, info(ssid = "other-ap")).preferredFamily())
        assertNull(GalaxyHotspotAddressMemory(context, info(iface = "wlan2")).preferredFamily())
        assertNull(GalaxyHotspotAddressMemory(context, info(security = Iap2WirelessSecurity.WPA3_ONLY)).preferredFamily())
    }
}
