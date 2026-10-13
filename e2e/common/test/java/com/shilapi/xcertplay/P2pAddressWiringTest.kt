package com.shilapi.xcertplay

import android.Manifest
import android.net.wifi.WifiManager
import com.shilapi.xcertplay.network.WifiP2pGroupManager
import com.shilapi.xcertplay.network.WirelessHotspotInfo
import com.shilapi.xcertplay.network.CarPlayBonjour
import com.shilapi.xcertplay.network.CarPlayBonjourEndpoint
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.net.InetAddress
import java.net.Inet6Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.LinkedBlockingQueue
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30], manifest = Config.NONE,
    shadows = [WifiP2pGroupManagerTest.P2pRadio::class, WifiP2pGroupManagerTest.P2pChannel::class])
class P2pAddressWiringTest {
    @Test fun ipv6OnlyGroupUsesTheSameCorrectedScopeForPrimaryAndAdditionalAddressMetadata() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        app.getSystemService(WifiManager::class.java).isWifiEnabled = true
        val stale = Inet6Address.getByAddress(null, InetAddress.getByName("fe80::1234").address, 3)
        val iface = mock(NetworkInterface::class.java)
        `when`(iface.index).thenReturn(7)
        `when`(iface.inetAddresses).thenAnswer { Collections.enumeration(listOf(stale)) }
        val worker = Executors.newSingleThreadExecutor()
        try {
            WifiP2pGroupManager(app, interfaceLookup = { iface }).use { manager ->
                val info = worker.submit<WirelessHotspotInfo> { manager.start(5000) }.get(8, TimeUnit.SECONDS)
                assertEquals(1, info.hostAddresses.size)
                assertEquals(7, (info.hostAddress as Inet6Address).scopeId)
                assertEquals(7, (info.hostAddresses.single() as Inet6Address).scopeId)
                assertSame(info.hostAddress, info.hostAddresses.first())
            }
        } finally { worker.shutdownNow() }
    }

    @Test fun ipv4PrimaryResolvesIpv6OnlyPhoneControlOnTheSelectedP2pScope() {
        val app = RuntimeEnvironment.getApplication()
        val primary = InetAddress.getByName("192.168.49.1")
        val local = Inet6Address.getByAddress(null, InetAddress.getByName("fe80::1234").address, 7)
        val config = AirPlayConfig("test", "02:00:00:00:00:02", "", "1", AirPlayDisplayConfig(800, 480))
        val dns = mock(JmDNS::class.java)
        `when`(dns.inetAddress).thenReturn(local)
        CarPlayBonjour(app, config, AirPlayIdentity(ByteArray(32), ByteArray(32), "test"),
            primary.hostAddress, true, additionalAddresses = listOf(local)).use { bonjour ->
            for (scope in listOf(0, 3)) {
                val info = mock(ServiceInfo::class.java)
                `when`(info.inetAddresses).thenReturn(arrayOf(Inet6Address.getByAddress(null,
                    InetAddress.getByName("fe80::5678").address, scope)))
                `when`(info.port).thenReturn(7000)
                val event = mock(ServiceEvent::class.java)
                `when`(event.dns).thenReturn(dns); `when`(event.info).thenReturn(info)
                `when`(event.name).thenReturn("test-phone-$scope")
                ReflectionHelpers.getField<ServiceListener>(bonjour, "interfaceListener").serviceResolved(event)
                val queue = ReflectionHelpers.getField<LinkedBlockingQueue<Pair<CarPlayBonjourEndpoint, InetAddress>>>(
                    bonjour, "interfaceServices")
                val endpoint = requireNotNull(queue.poll())
                assertEquals(7, (endpoint.second as Inet6Address).scopeId)
                assertEquals(7000, endpoint.first.port)
                val source = bonjour.javaClass.getDeclaredMethod("sourceAddressFor", InetAddress::class.java)
                    .apply { isAccessible = true }.invoke(bonjour, endpoint.second)
                assertEquals(local, source)
            }
        }
    }

    @Test fun liveGroupPassesBothFamiliesToDiscoveryAndListenerWithoutChangingPrimaryOrCredentials() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        app.getSystemService(WifiManager::class.java).isWifiEnabled = true
        val ipv4 = InetAddress.getByName("192.168.49.1")
        val ipv6 = Inet6Address.getByAddress(null, InetAddress.getByName("fe80::1234").address, 3)
        val iface = mock(NetworkInterface::class.java)
        `when`(iface.index).thenReturn(7)
        `when`(iface.inetAddresses).thenAnswer { Collections.enumeration(listOf(ipv6, ipv4)) }
        val logs = mutableListOf<String>()
        val worker = Executors.newSingleThreadExecutor()
        try {
            WifiP2pGroupManager(app, logs::add, interfaceLookup = { iface }).use { manager ->
                val info = worker.submit<WirelessHotspotInfo> { manager.start(5000) }.get(8, TimeUnit.SECONDS)
                assertEquals(ipv4, info.hostAddress)
                assertEquals(ipv4, info.hostAddresses.first())
                assertEquals(2, info.hostAddresses.size)
                assertEquals(7, (info.hostAddresses.last() as Inet6Address).scopeId)
                assertTrue(info.ssid.isNotBlank() && info.passphrase.isNotBlank())
                assertTrue(logs.any { it == "Wi-Fi P2P listener families=IPv4,IPv6" })
                assertFalse(logs.any { info.ssid in it || info.passphrase in it })
            }
        } finally { worker.shutdownNow() }
    }
}
