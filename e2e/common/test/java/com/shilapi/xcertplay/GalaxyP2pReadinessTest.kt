package com.shilapi.xcertplay

import android.Manifest
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pManager
import com.shilapi.xcertplay.network.*
import com.shilapi.xcertplay.network.WirelessLinkReadiness.Condition.*
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE,
    shadows = [WifiP2pGroupManagerTest.P2pRadio::class, WifiP2pGroupManagerTest.P2pChannel::class])
class GalaxyP2pReadinessTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val radio get() = shadowOf(app.getSystemService(WifiP2pManager::class.java)) as WifiP2pGroupManagerTest.P2pRadio
    @Before fun setup() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        app.getSystemService(WifiManager::class.java).isWifiEnabled = true
    }

    @Test fun probeDistinguishesDefiniteLossFromMissingFrameworkReplyAndStopsAfterClose() {
        val network = mock(NetworkInterface::class.java)
        var available = true
        var addresses = listOf(InetAddress.getByName("192.168.49.1"))
        `when`(network.isUp).thenReturn(true)
        `when`(network.inetAddresses).thenAnswer { Collections.enumeration(addresses) }
        val manager = WifiP2pGroupManager(app, preferredChannel = 149,
            interfaceLookup = { if (available) network else null })
        val worker = Executors.newSingleThreadExecutor()
        fun probe() = worker.submit<WirelessLinkReadiness> { manager.connectionReadiness() }.get(2, TimeUnit.SECONDS)
        try {
            assertEquals(UNKNOWN, probe().condition)
            worker.submit { manager.start(5000) }.get(7, TimeUnit.SECONDS)
            assertEquals(WirelessLinkReadiness(READY, 0), probe())
            radio.noGroupReply = true
            assertEquals(UNKNOWN, probe().condition)
            radio.noGroupReply = false
            available = false
            assertEquals(INTERFACE_MISSING, probe().condition)
            available = true
            addresses = listOf(InetAddress.getByName("192.0.2.10"))
            assertEquals(ADDRESS_MISSING, probe().condition)
            addresses = listOf(InetAddress.getByName("192.168.49.1"))
            `when`(network.isUp).thenReturn(false)
            assertEquals(INTERFACE_MISSING, probe().condition)
            `when`(network.isUp).thenReturn(true)
            val owned = radio.group
            radio.group = radio.makeGroup(null)
            assertEquals(GROUP_CHANGED, probe().condition)
            radio.group = null
            assertEquals(GROUP_MISSING, probe().condition)
            radio.group = owned
            assertEquals(READY, probe().condition)
            manager.close()
            assertEquals(UNKNOWN, probe().condition)
        } finally { manager.close(); worker.shutdownNow() }
    }

    @Test fun existingForeignGroupNeverRunsPrecreationMaintenance() {
        radio.group = radio.makeGroup(null)
        var preparations = 0
        WifiP2pGroupManager(app, beforeGroupCreate = { _, _ -> preparations++ }).use { manager ->
            val worker = Executors.newSingleThreadExecutor()
            try {
                val error = assertThrows(java.util.concurrent.ExecutionException::class.java) {
                    worker.submit { manager.start(5000) }.get(7, TimeUnit.SECONDS)
                }
                assertTrue(error.cause is P2pResetRequiredException)
                assertEquals(0, preparations); assertEquals(0, radio.removals)
            } finally { worker.shutdownNow() }
        }
    }
}
