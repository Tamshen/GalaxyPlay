package com.shilapi.xcertplay

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** 验证真实连接入口绕过原生热点，不创建 P2P 组或连接手机。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class GalaxyWirelessConnectionTest {
    private lateinit var activity: GalaxySettingsActivity
    @Before fun setup() {
        CarPlayBackgroundSession.clear()
        activity = Robolectric.buildActivity(GalaxySettingsActivity::class.java).get()
        activity.setTheme(R.style.Theme_Xcertplay)
        L7Agreement.accept(activity)
        AirPlayPersistence.saveWirelessHotspotMode(activity, WirelessHotspotMode.WIFI_P2P)
        shadowOf(activity.packageManager).setSystemFeature(PackageManager.FEATURE_WIFI_DIRECT, true)
        activity.getSystemService(WifiManager::class.java).isWifiEnabled = true
        val adapter = activity.getSystemService(BluetoothManager::class.java).adapter
        shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
        val phone = adapter.getRemoteDevice("02:00:00:00:00:01")
        shadowOf(adapter).setBondedDevices(setOf(phone))
        DiPlayPreferences.savePhone(activity, phone.address, "synthetic phone")
    }
    @After fun cleanup() {
        ReflectionHelpers.getField<Handler>(activity, "handler").removeCallbacksAndMessages(null)
        val task = ReflectionHelpers.getField<Lazy<L7HotspotTask>>(activity, "hotspotTask\$delegate")
        if (task.isInitialized()) task.value.close()
        ReflectionHelpers.getField<GalaxyWirelessSettings?>(activity, "wirelessSettings")?.dispose()
        CarPlayBackgroundSession.clear()
    }
    private fun connect() = activity.javaClass.getDeclaredMethod("connect", Boolean::class.javaPrimitiveType,
        Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, true, false)

    @Test fun directStartsHostWithoutNativeCredentialsOrHotspotPreparation() {
        ReflectionHelpers.setField(activity, "pendingCarHotspotSetup", true)
        connect()
        assertEquals(CarPlayHostActivity::class.java.name, shadowOf(activity).nextStartedActivity.component!!.className)
        assertFalse(ReflectionHelpers.getField<Lazy<L7WirelessHotspotGate>>(activity, "wirelessHotspotGate\$delegate").isInitialized())
        assertEquals(WirelessHotspotMode.WIFI_P2P, AirPlayPersistence.loadWirelessHotspotMode(activity))
        assertTrue(AirPlayPersistence.loadWirelessEnabled(activity))
    }

    @Test fun directPageDoesNotCreateOrReadNativeHotspotSettings() {
        activity.javaClass.getDeclaredMethod("connectionSettingsL7", LinearLayout::class.java)
            .apply { isAccessible = true }.invoke(activity, LinearLayout(activity))
        assertNull(ReflectionHelpers.getField<L7HotspotSettings?>(activity, "hotspotSettings"))
        assertFalse(ReflectionHelpers.getField<Lazy<L7HotspotTask>>(activity, "hotspotTask\$delegate").value.status.busy)
    }

    @Test fun wifiOffCannotStartHostOrNativeHotspotPreparation() {
        activity.getSystemService(WifiManager::class.java).isWifiEnabled = false
        connect()
        assertNull(shadowOf(activity).nextStartedActivity)
        assertFalse(ReflectionHelpers.getField<Lazy<L7WirelessHotspotGate>>(activity, "wirelessHotspotGate\$delegate").isInitialized())
    }

    @Test fun unsupportedDirectCannotFallBackToNativeHotspotOrLaunchHost() {
        shadowOf(activity.packageManager).setSystemFeature(PackageManager.FEATURE_WIFI_DIRECT, false)
        connect()
        assertNull(shadowOf(activity).nextStartedActivity)
        assertFalse(ReflectionHelpers.getField<Lazy<L7WirelessHotspotGate>>(activity, "wirelessHotspotGate\$delegate").isInitialized())
        assertEquals(WirelessHotspotMode.WIFI_P2P, AirPlayPersistence.loadWirelessHotspotMode(activity))
    }
}
