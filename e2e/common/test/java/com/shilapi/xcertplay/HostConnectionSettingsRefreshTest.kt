package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.network.P2pAddressMode
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation

/**
 * The connection settings screen lives in [DiPlayActivity] and comes back to the projection screen
 * with FLAG_ACTIVITY_REORDER_TO_FRONT, so the projection screen is resumed, not recreated.
 * Settings saved while it was in the background must reach the next handshake.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, shadows = [HostConnectionSettingsRefreshTest.Bootstrap::class])
class HostConnectionSettingsRefreshTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun setUp() {
        app.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("l7_audio_templates", 0).edit().putString("model", "l7").commit()
    }

    @Test fun projectionScreenUsesConnectionSettingsSavedWhileItWasInTheBackground() {
        AirPlayPersistence.saveWirelessEnabled(app, true)
        AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.MANUAL)
        AirPlayPersistence.saveManualHotspotSsid(app, "Old car")
        L7Agreement.accept(app)
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).setup()
        val manual = runtimeConfig(host.get())
        assertEquals(WirelessHotspotMode.MANUAL, manual.wirelessHotspotMode)
        assertEquals(30_000L, manual.wirelessFirstTcpTimeoutMillis)
        assertEquals(false, manual.wirelessStartupRefreshEnabled)
        assertEquals(P2pAddressMode.IPV4_ONLY, manual.wifiP2pAddressMode)
        assertEquals(true, manual.wifiScanPauseEnabled)
        assertEquals(true, manual.wifiHighPerformanceEnabled)

        // The user switches to a car hotspot with another name while the projection screen lives on.
        AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.WIFI_P2P)
        AirPlayPersistence.saveManualHotspotSsid(app, "New car")
        AirPlayPersistence.saveWifiP2pPreferredChannel(app, 149)
        app.getSharedPreferences("xcertplay_airplay", 0).edit()
            .putBoolean(GalaxyWirelessPerformanceSettings.SCAN, false)
            .putBoolean(GalaxyWirelessPerformanceSettings.LOCK, false).commit()

        host.pause().resume()

        val config = runtimeConfig(host.get())
        assertEquals(WirelessHotspotMode.WIFI_P2P, config.wirelessHotspotMode)
        assertEquals("New car", config.manualHotspotSsid)
        assertEquals(149, config.wifiP2pPreferredChannel)
        assertEquals(30_000L, config.wirelessFirstTcpTimeoutMillis)
        assertEquals(true, config.wirelessStartupRefreshEnabled)
        assertEquals(P2pAddressMode.IPV4_ONLY, config.wifiP2pAddressMode)
        assertEquals(false, config.wifiScanPauseEnabled)
        assertEquals(false, config.wifiHighPerformanceEnabled)
    }

    @Test fun l7DirectIsFrozenInRuntimeConfigAndOtherModelsKeepTheirSavedChannel() {
        AirPlayPersistence.saveWirelessEnabled(app, true)
        AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.WIFI_P2P)
        AirPlayPersistence.saveWifiP2pPreferredChannel(app, 153)
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        org.robolectric.util.ReflectionHelpers.setField(host, "wirelessEnabled", true)
        org.robolectric.util.ReflectionHelpers.setField(host, "wirelessHotspotMode", WirelessHotspotMode.WIFI_P2P)
        val l7 = runtimeConfig(host)
        assertEquals(P2pAddressMode.IPV4_ONLY, l7.wifiP2pAddressMode)
        assertEquals(149, l7.wifiP2pPreferredChannel)
        assertEquals(30_000L, l7.wirelessFirstTcpTimeoutMillis)
        for (model in listOf("l6", "custom")) {
            app.getSharedPreferences("l7_audio_templates", 0).edit().putString("model", model).commit()
            val other = runtimeConfig(host)
            assertEquals(P2pAddressMode.IPV4_ONLY, other.wifiP2pAddressMode)
            assertEquals(153, other.wifiP2pPreferredChannel)
            assertEquals(P2pAddressMode.IPV4_ONLY, l7.wifiP2pAddressMode)
        }
        app.getSharedPreferences("l7_audio_templates", 0).edit().putString("model", "l7").commit()
        GalaxyP2pSettings.save(app, com.shilapi.xcertplay.network.GalaxyP2pRegion.OTHER_CONSERVATIVE)
        AirPlayPersistence.saveWifiP2pPreferredChannel(app, 6)
        assertEquals(6, runtimeConfig(host).wifiP2pPreferredChannel)
        org.robolectric.util.ReflectionHelpers.setField(host, "wirelessEnabled", false)
        assertEquals(P2pAddressMode.IPV4_ONLY, runtimeConfig(host).wifiP2pAddressMode)
    }

    @Test fun userSelectedIpModeReachesRuntimeAndLaterEditsCannotChangeTheCreatedConfig() {
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        org.robolectric.util.ReflectionHelpers.setField(host, "wirelessEnabled", true)
        org.robolectric.util.ReflectionHelpers.setField(host, "wirelessHotspotMode", WirelessHotspotMode.WIFI_P2P)
        val first = runtimeConfig(host)
        for (mode in P2pAddressMode.entries) {
            app.getSharedPreferences("xcertplay_airplay", 0).edit().putString(GalaxyP2pAddressSettings.KEY, mode.name).commit()
            assertEquals(mode, runtimeConfig(host).wifiP2pAddressMode)
            assertEquals(P2pAddressMode.IPV4_ONLY, first.wifiP2pAddressMode)
        }
    }

    @Test fun usbSelectionReloadsOnReturnAndNeverChangesWirelessTransport() {
        AirPlayPersistence.saveWirelessEnabled(app, false)
        AirPlayPersistence.saveManualHotspotSsid(app, "Test car")
        L7Agreement.accept(app)
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).setup()
        try {
            assertEquals(false, runtimeConfig(host.get()).usbVerificationEnabled)
            app.getSharedPreferences("xcertplay_airplay", 0).edit().putBoolean("galaxy_usb_verification_enabled", true).commit()
            host.pause().resume()
            assertEquals(true, runtimeConfig(host.get()).usbVerificationEnabled)
            AirPlayPersistence.saveWirelessEnabled(app, true)
            host.pause().resume()
            assertEquals(false, runtimeConfig(host.get()).usbVerificationEnabled)
        } finally { host.pause().stop().destroy() }
    }

    private fun runtimeConfig(host: CarPlayHostActivity): CarPlayRuntimeConfig {
        CarPlayHostActivity::class.java.getDeclaredField("airPlayIdentity").apply { isAccessible = true }
            .set(host, AirPlayPersistence.loadIdentity(host))
        return CarPlayHostActivity::class.java.getDeclaredMethod("createRuntimeConfig")
            .apply { isAccessible = true }.invoke(host) as CarPlayRuntimeConfig
    }

    /** The projection screen must start without the private MFi identity used by real cars. */
    @Implements(DiPlayBootstrap::class, isInAndroidSdk = false)
    internal class Bootstrap {
        @Implementation fun ensure(context: Context) = Unit

        @Implementation fun deviceId(identity: AirPlayIdentity): String = "02:00:00:00:00:01"
    }
}
