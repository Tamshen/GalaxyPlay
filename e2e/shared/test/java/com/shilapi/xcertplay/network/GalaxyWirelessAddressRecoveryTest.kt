package com.shilapi.xcertplay.network

import android.content.Context
import com.shilapi.xcertplay.transport.Iap2WirelessCarPlayEndpoint
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.net.InetAddress
import java.net.Inet6Address
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30], manifest = Config.NONE)
class GalaxyWirelessAddressRecoveryTest {
    private val context = RuntimeEnvironment.getApplication()
    private val ipv4 = InetAddress.getByName("192.0.2.1")
    private val ipv6 = Inet6Address.getByAddress(null, InetAddress.getByName("fe80::1234").address, 22)
    private var live = listOf(ipv4, ipv6)
    private var current = true
    private val endpoint = Iap2WirelessCarPlayEndpoint("test-direct", "test12345", 149,
        Iap2WirelessSecurity.WPA_WPA2, listOf("192.0.2.1"), 7000, "test-id", "abcd", "1.0")

    private fun info(backend: WirelessHotspotBackend, addresses: List<InetAddress> = live) =
        WirelessHotspotInfo(endpoint.ssid, endpoint.passphrase, endpoint.security, endpoint.channel,
            5745, null, "p2p0", addresses.firstOrNull(), "5 GHz", backend, addresses)

    private fun policy(info: WirelessHotspotInfo, enabled: Boolean = true) =
        GalaxyWirelessAddressRecovery.create(context, info, enabled, { current }, { live })

    @Test fun directRefreshPublishesBoundIpv6OnceWithoutOverwritingManualNetworkMemory() {
        val manual = info(WirelessHotspotBackend.MANUAL_HOTSPOT)
        GalaxyHotspotAddressMemory(context, manual).remember("IPv6")
        val preferences = context.getSharedPreferences("galaxy_hotspot_address", Context.MODE_PRIVATE)
        val before = preferences.all.toMap()
        val recovery = policy(info(WirelessHotspotBackend.WIFI_P2P))!!
        assertEquals(ipv4, recovery.initialAddress)
        assertEquals(listOf("fe80:0:0:0:0:0:0:1234"), recovery.refresh(endpoint)!!.ipAddresses)
        assertNull(recovery.refresh(endpoint))
        assertEquals(listOf("fe80:0:0:0:0:0:0:1234"), recovery.forSession(endpoint, ipv6).ipAddresses)
        recovery.rendered(ipv6)
        assertEquals(before, preferences.all)
    }

    @Test fun directWithoutTheProductStrategyAndOtherBackendsKeepTheirOriginalPath() {
        assertNull(policy(info(WirelessHotspotBackend.WIFI_P2P), enabled = false))
        assertNull(policy(info(WirelessHotspotBackend.LOCAL_ONLY_HOTSPOT)))
        assertNull(policy(info(WirelessHotspotBackend.EXISTING_WIFI)))
    }

    @Test fun manualNetworkStillReadsAndWritesItsSuccessfulFamily() {
        val manual = info(WirelessHotspotBackend.MANUAL_HOTSPOT)
        GalaxyHotspotAddressMemory(context, manual).remember("IPv6")
        val recovery = policy(manual, enabled = false)!!
        assertEquals(ipv6, recovery.initialAddress)
        recovery.rendered(ipv4)
        assertEquals("IPv4", GalaxyHotspotAddressMemory(context, manual).preferredFamily())
    }

    @Test fun directSingleStackAndLateUnboundIpv6KeepTheOriginalEndpoint() {
        val addresses = p2pHostAddresses(ipv4, live, 22, addressMode = com.shilapi.xcertplay.network.P2pAddressMode.IPV4_ONLY)
        val recovery = policy(info(WirelessHotspotBackend.WIFI_P2P, addresses))!!
        val refreshed = recovery.refresh(endpoint)!!
        assertEquals(endpoint.ipAddresses, refreshed.ipAddresses)
        assertEquals(endpoint.airPlayPort, refreshed.airPlayPort)
        assertEquals(endpoint.ssid, refreshed.ssid)
        assertEquals(endpoint.passphrase, refreshed.passphrase)
        assertEquals(endpoint.deviceIdentifier, refreshed.deviceIdentifier)
        assertEquals(listOf(ipv4), recovery.publishedAddresses)
        assertNull(recovery.refresh(endpoint))
        recovery.close()
    }

    @Test fun directRemovedAddressesAndCancelledRunsCannotPublish() {
        val removed = policy(info(WirelessHotspotBackend.WIFI_P2P))!!
        live = emptyList()
        assertNull(removed.refresh(endpoint))
        live = listOf(ipv4, ipv6)
        val cancelled = policy(info(WirelessHotspotBackend.WIFI_P2P))!!
        current = false
        assertNull(cancelled.refresh(endpoint))
    }

    @Test fun closingDirectRejectsLateRefreshAndSessionAddressUpdates() {
        val recovery = policy(info(WirelessHotspotBackend.WIFI_P2P))!!
        recovery.close()
        assertNull(recovery.refresh(endpoint))
        assertSame(endpoint, recovery.forSession(endpoint, ipv6))
    }
}
