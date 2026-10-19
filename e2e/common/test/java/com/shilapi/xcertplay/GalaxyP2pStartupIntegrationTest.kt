package com.shilapi.xcertplay

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pManager
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.network.*
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 从真实控制器入口创建合成 P2P 组，验证准备门禁、代次与资源释放。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE,
    shadows = [WifiP2pGroupManagerTest.P2pRadio::class, WifiP2pGroupManagerTest.P2pChannel::class])
class GalaxyP2pStartupIntegrationTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val radio get() = shadowOf(app.getSystemService(WifiP2pManager::class.java)) as WifiP2pGroupManagerTest.P2pRadio
    @Before fun setup() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        app.getSystemService(WifiManager::class.java).isWifiEnabled = true
    }

    private fun controller(control: WifiScanPauseControl? = null, prepare: (WirelessHotspotInfo, () -> Boolean, (String) -> Unit) -> Unit): CarPlayController {
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun bindService(service: Intent, conn: ServiceConnection, flags: Int) = false
        }
        return CarPlayController(context, CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL,
            transport = CarPlayTransport.WIRELESS, wirelessHotspotMode = WirelessHotspotMode.WIFI_P2P,
            wifiP2pPreferredChannel = 149, wifiScanPauseEnabled = control != null,
            identification = Iap2IdentificationConfig("test", "test", "test", "test", "1", "1", 3)),
            AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480)),
            AirPlayIdentity(ByteArray(32), ByteArray(32), "test"), PairingStore(),
            object : AirPlaySessionListener {}, object : AirPlayMediaHandler {}, {},
            prepareWirelessHotspot = prepare, wifiScanPauseControl = control).also { controller ->
            val phase = controller.javaClass.declaredClasses.first { it.simpleName == "Phase" }
                .enumConstants.first { (it as Enum<*>).name == "WIRELESS" }
            ReflectionHelpers.setField(controller, "phase", phase)
        }
    }

    private fun start(controller: CarPlayController): WirelessHotspotInfo {
        val worker = Executors.newSingleThreadExecutor()
        return try {
            worker.submit<WirelessHotspotInfo> {
                controller.javaClass.getDeclaredMethod("startWirelessHotspot", Int::class.javaPrimitiveType)
                    .apply { isAccessible = true }.invoke(controller, 0) as WirelessHotspotInfo
            }.get(8, TimeUnit.SECONDS)
        } catch (error: ExecutionException) {
            val cause = error.cause!!
            throw if (cause is InvocationTargetException) cause.targetException else cause
        } finally { worker.shutdownNow() }
    }

    @Test fun controllerUsesInjectedScanOwnerForStartupPauseAndFinalRelease() {
        var recoveries = 0
        var acquiredOwner: Any? = null
        var released: Any? = null
        var ownerCurrent: (() -> Boolean)? = null
        val control = object : WifiScanPauseControl {
            override fun recover(app: Context) { recoveries++ }
            override fun acquire(app: Context, owner: Any, current: () -> Boolean, log: (String) -> Unit) {
                acquiredOwner = owner; ownerCurrent = current
                log("Wi-Fi connectivity scans paused=true reason=done transport=host-shell")
            }
            override fun release(app: Context, owner: Any, log: (String) -> Unit) { released = owner }
        }
        val controller = controller(control) { _, _, _ -> }
        try {
            assertEquals(1, recoveries)
            val info = start(controller)
            controller.javaClass.getDeclaredMethod("pauseWifiScans", WirelessHotspotBackend::class.java)
                .apply { isAccessible = true }.invoke(controller, info.backend)
            assertNotNull(acquiredOwner); assertTrue(ownerCurrent!!())
        } finally { controller.close(); assertTrue(controller.awaitClosed(2000)) }
        assertSame(acquiredOwner, released)
        assertFalse(ownerCurrent!!())
    }

    @Test fun preparationRunsAfterLiveGroupCreationBeforeReadyIsReturned() {
        var calls = 0
        val controller = controller { info, cancelled, _ ->
            calls++
            assertFalse(cancelled()); assertNotNull(radio.group)
            assertEquals(radio.group!!.networkName, info.ssid)
            assertEquals(149, info.channel)
        }
        try { assertNotNull(start(controller)); assertEquals(1, calls) }
        finally { controller.close(); controller.awaitClosed(2000) }
        assertNull(radio.group)
    }

    @Test fun preparationFailureClosesOwnedGroupAndCannotReturnReady() {
        val controller = controller { _, _, _ ->
            throw WirelessStartupException(WirelessStartupFailure.HOTSPOT_CONFIGURATION, GalaxyP2pAutoJoin.FAILURE)
        }
        try {
            val error = assertThrows(java.io.IOException::class.java) { start(controller) }
            assertTrue(error.message!!.contains(GalaxyP2pAutoJoin.FAILURE))
            assertNull(radio.group); assertEquals(1, radio.removals)
        } finally { controller.close(); controller.awaitClosed(2000) }
    }

    @Test fun supersededGenerationCannotPublishPreparedNetwork() {
        lateinit var controller: CarPlayController
        controller = controller { _, cancelled, _ ->
            ReflectionHelpers.getField<AtomicInteger>(controller, "wirelessGeneration").incrementAndGet()
            assertTrue(cancelled())
        }
        try {
            assertThrows(java.io.InterruptedIOException::class.java) { start(controller) }
            assertNull(radio.group)
        } finally { controller.close(); controller.awaitClosed(2000) }
    }

    @Test fun anotherGroupAppearingDuringPreparationIsPreservedAndOldInfoIsRejected() {
        val controller = controller { _, _, _ -> radio.group = radio.makeGroup(null) }
        try {
            assertThrows(java.io.IOException::class.java) { start(controller) }
            assertEquals("DIRECT-system-test", radio.group!!.networkName)
            assertEquals(0, radio.removals)
        } finally { controller.close(); controller.awaitClosed(2000) }
    }
}
