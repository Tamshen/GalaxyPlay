package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30])
class GalaxyWirelessSettingsTest {
    private lateinit var activity: Activity
    private lateinit var parent: LinearLayout
    private lateinit var settings: GalaxyWirelessSettings
    private var changed = 0
    private var editable = true

    @Before fun setup() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setTheme(R.style.Theme_Xcertplay)
        activity.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        activity.getSharedPreferences("l7_audio_templates", 0).edit().putString("model", "l6").commit()
        shadowOf(activity.packageManager).setSystemFeature(PackageManager.FEATURE_WIFI_DIRECT, true)
        activity.getSystemService(WifiManager::class.java).isWifiEnabled = true
        parent = LinearLayout(activity)
        settings = GalaxyWirelessSettings(activity, parent, { editable }) { changed++ }
    }

    @After fun cleanup() { settings.dispose(); activity.finish() }
    private fun rows(view: View = parent): List<L7SettingRow> = if (view is L7SettingRow) listOf(view)
        else if (view is ViewGroup) (0 until view.childCount).flatMap { rows(view.getChildAt(it)) } else emptyList()
    private fun picker(index: Int = 1): AlertDialog {
        rows().first().performClick()
        shadowOf(Looper.getMainLooper()).idle()
        return ShadowAlertDialog.getLatestAlertDialog().also { choose(it, index) }
    }
    private fun choose(dialog: AlertDialog, index: Int) {
        val list = dialog.listView
        list.performItemClick(list.adapter.getView(index, null, list), index, index.toLong())
    }

    @Test fun freshInstallRemainsManualAndCancellingDirectKeepsCredentials() {
        AirPlayPersistence.saveManualHotspotSsid(activity, "synthetic-native")
        AirPlayPersistence.saveManualHotspotPassphrase(activity, "synthetic-password")
        picker().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(WirelessHotspotMode.MANUAL, AirPlayPersistence.loadWirelessHotspotMode(activity))
        assertEquals("synthetic-native", AirPlayPersistence.loadManualHotspotSsid(activity))
        assertEquals("synthetic-password", AirPlayPersistence.loadManualHotspotPassphrase(activity))
        assertEquals(0, changed)
    }

    @Test fun confirmingDirectSavesOnlyModeOnceAndDoesNotLaunchAnything() {
        val confirm = picker().getButton(AlertDialog.BUTTON_POSITIVE)
        confirm.performClick(); confirm.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(WirelessHotspotMode.WIFI_P2P, AirPlayPersistence.loadWirelessHotspotMode(activity))
        assertEquals(1, changed)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(activity.getSystemService(WifiManager::class.java).isWifiEnabled)
    }

    @Test fun unavailableHardwareCannotSelectDirect() {
        shadowOf(activity.packageManager).setSystemFeature(PackageManager.FEATURE_WIFI_DIRECT, false)
        val dialog = picker()
        assertFalse(dialog.listView.adapter.isEnabled(1))
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(WirelessHotspotMode.MANUAL, AirPlayPersistence.loadWirelessHotspotMode(activity))
        assertEquals(0, changed)
    }

    @Test fun disposedOrDismissedPickerCannotSaveALateConfirmation() {
        for (dispose in listOf(false, true)) {
            val dialog = picker()
            if (dispose) settings.dispose() else settings.dismiss()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
            assertEquals(WirelessHotspotMode.MANUAL, AirPlayPersistence.loadWirelessHotspotMode(activity))
        }
        assertEquals(0, changed)
    }

    @Test fun preparationBecomingBusyRejectsAlreadyOpenPicker() {
        val dialog = picker()
        editable = false
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(WirelessHotspotMode.MANUAL, AirPlayPersistence.loadWirelessHotspotMode(activity))
        assertEquals(0, changed)
    }

    @Test fun directChannelRequiresConfirmationAndKeepsNativeCredentials() {
        AirPlayPersistence.saveWirelessHotspotMode(activity, WirelessHotspotMode.WIFI_P2P)
        AirPlayPersistence.saveManualHotspotSsid(activity, "synthetic-native")
        settings.addDirect(parent)
        val row = rows().first { it.titleView.text == activity.getString(R.string.wifi_direct_channel_title) }
        row.performClick()
        val cancelled = ShadowAlertDialog.getLatestAlertDialog()
        choose(cancelled, 1)
        cancelled.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, AirPlayPersistence.loadWifiP2pPreferredChannel(activity))
        row.performClick()
        val confirmed = ShadowAlertDialog.getLatestAlertDialog()
        choose(confirmed, 1)
        confirmed.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(149, AirPlayPersistence.loadWifiP2pPreferredChannel(activity))
        assertEquals("synthetic-native", AirPlayPersistence.loadManualHotspotSsid(activity))
    }

    @Test fun l7ShowsFixedChannelWithoutReplacingSavedChannelForOtherModels() {
        activity.getSharedPreferences("l7_audio_templates", 0).edit().putString("model", "l7").commit()
        AirPlayPersistence.saveWifiP2pPreferredChannel(activity, 153)
        settings.addDirect(parent)
        val row = rows().single { it.titleView.text == activity.getString(R.string.wifi_direct_channel_title) }
        assertFalse(row.isEnabled)
        assertEquals(149, GalaxyP2pSettings.effectiveChannel(activity))
        assertEquals(153, AirPlayPersistence.loadWifiP2pPreferredChannel(activity))
        GalaxyP2pSettings.save(activity, com.shilapi.xcertplay.network.GalaxyP2pRegion.OTHER_CONSERVATIVE)
        settings.update()
        assertTrue(row.isEnabled)
        assertNull(GalaxyP2pSettings.fixedChannel(activity))
        activity.getSharedPreferences("l7_audio_templates", 0).edit().putString("model", "l6").commit()
        GalaxyP2pSettings.save(activity, com.shilapi.xcertplay.network.GalaxyP2pRegion.MAINLAND_CHINA)
        settings.update()
        assertTrue(row.isEnabled)
        assertEquals(153, GalaxyP2pSettings.effectiveChannel(activity))
    }

    @Test fun templateAndResetPreserveDirectModeAndChannel() {
        File(activity.filesDir, "configurations").deleteRecursively()
        AirPlayPersistence.saveWirelessHotspotMode(activity, WirelessHotspotMode.WIFI_P2P)
        AirPlayPersistence.saveWifiP2pPreferredChannel(activity, 149)
        activity.getSharedPreferences("xcertplay_airplay", 0).edit()
            .putBoolean(GalaxyWirelessPerformanceSettings.SCAN, false)
            .putBoolean(GalaxyWirelessPerformanceSettings.LOCK, false).commit()
        val repository = GalaxyProfiles(activity)
        for (model in listOf("l7", "l6", "custom")) {
            val profile = repository.applyTemplate(model)
            repository.resetCurrent(profile)
            assertEquals(WirelessHotspotMode.WIFI_P2P, AirPlayPersistence.loadWirelessHotspotMode(activity))
            assertEquals(149, AirPlayPersistence.loadWifiP2pPreferredChannel(activity))
            assertFalse(GalaxyWirelessPerformanceSettings.enabled(activity, GalaxyWirelessPerformanceSettings.SCAN))
            assertFalse(GalaxyWirelessPerformanceSettings.enabled(activity, GalaxyWirelessPerformanceSettings.LOCK))
        }
    }

    @Test fun performanceSwitchesSaveIndependentlyAndRejectBusyOrDisposedEdits() {
        settings.addDirect(parent)
        val lock = rows().single { it.titleView.text == activity.getString(R.string.galaxy_wifi_performance_lock) }
        val scan = rows().single { it.titleView.text == activity.getString(R.string.galaxy_wifi_scan_pause) }
        lock.performClick()
        assertFalse(GalaxyWirelessPerformanceSettings.enabled(activity, GalaxyWirelessPerformanceSettings.LOCK))
        assertTrue(GalaxyWirelessPerformanceSettings.enabled(activity, GalaxyWirelessPerformanceSettings.SCAN))
        editable = false
        scan.performClick()
        assertTrue(GalaxyWirelessPerformanceSettings.enabled(activity, GalaxyWirelessPerformanceSettings.SCAN))
        settings.update()
        assertFalse(lock.isEnabled || scan.isEnabled)
        editable = true
        settings.update()
        scan.performClick()
        assertFalse(GalaxyWirelessPerformanceSettings.enabled(activity, GalaxyWirelessPerformanceSettings.SCAN))
        settings.dispose()
        lock.performClick()
        assertFalse(GalaxyWirelessPerformanceSettings.enabled(activity, GalaxyWirelessPerformanceSettings.LOCK))
    }

    @Test fun wifiOffShowsRecoveryWithoutEnablingTheRadio() {
        val wifi = activity.getSystemService(WifiManager::class.java)
        wifi.isWifiEnabled = false
        assertFalse(GalaxyWirelessSettings.ensure(activity))
        assertFalse(wifi.isWifiEnabled)
        assertNull(shadowOf(activity).nextStartedActivity)
        val openSettings = ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE)
        assertEquals(activity.getString(R.string.open_car_hotspot_settings), openSettings.text)
        openSettings.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(android.provider.Settings.ACTION_WIRELESS_SETTINGS, shadowOf(activity).nextStartedActivity.action)
    }

    @Test fun oldIndoorChannelIsPreservedButNotSelectableForMainlandVehicles() {
        AirPlayPersistence.saveWirelessHotspotMode(activity, WirelessHotspotMode.WIFI_P2P)
        AirPlayPersistence.saveWifiP2pPreferredChannel(activity, 36)
        settings.addDirect(parent)
        val row = rows().first { it.titleView.text == activity.getString(R.string.wifi_direct_channel_title) }
        assertEquals(36, AirPlayPersistence.loadWifiP2pPreferredChannel(activity))
        row.performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val labels = (0 until dialog.listView.adapter.count).map { dialog.listView.adapter.getItem(it).toString() }
        assertFalse(labels.any { it.contains("36") || it.contains("48") })
        assertTrue(labels.any { it.contains("149") })
        choose(dialog, 0)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, AirPlayPersistence.loadWifiP2pPreferredChannel(activity))
    }

    @Test fun regionChangesOnlyOnConfirmationAndLateChannelPickerCannotSave() {
        AirPlayPersistence.saveWirelessHotspotMode(activity, WirelessHotspotMode.WIFI_P2P)
        settings.addDirect(parent)
        val region = rows().first { it.titleView.text == activity.getString(R.string.galaxy_p2p_region_title) }
        region.performClick()
        val cancelled = ShadowAlertDialog.getLatestAlertDialog()
        choose(cancelled, 1)
        cancelled.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertEquals(com.shilapi.xcertplay.network.GalaxyP2pRegion.MAINLAND_CHINA, GalaxyP2pSettings.load(activity))
        region.performClick()
        val confirmed = ShadowAlertDialog.getLatestAlertDialog()
        choose(confirmed, 1)
        confirmed.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(com.shilapi.xcertplay.network.GalaxyP2pRegion.OTHER_CONSERVATIVE, GalaxyP2pSettings.load(activity))
        val channel = rows().first { it.titleView.text == activity.getString(R.string.wifi_direct_channel_title) }
        channel.performClick()
        val picker = ShadowAlertDialog.getLatestAlertDialog()
        assertFalse((0 until picker.listView.adapter.count).any { picker.listView.adapter.getItem(it).toString().contains("5 GHz") })
        choose(picker, 1)
        GalaxyP2pSettings.save(activity, com.shilapi.xcertplay.network.GalaxyP2pRegion.MAINLAND_CHINA)
        picker.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, AirPlayPersistence.loadWifiP2pPreferredChannel(activity))
    }
}
