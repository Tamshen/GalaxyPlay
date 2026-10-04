package com.shilapi.xcertplay

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30])
class L7HotspotNavigationTest {
    class SettingsActivity : Activity() {
        val attempts = mutableListOf<Intent>()
        var available = Settings.ACTION_WIRELESS_SETTINGS
        override fun startActivity(intent: Intent) {
            attempts += intent
            if (intent.action != available) throw ActivityNotFoundException()
        }
    }
    private val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
    private val hotspot = "com.android.settings.WIFI_TETHER_SETTINGS"

    private fun registerHotspot() {
        shadowOf(activity.packageManager).addResolveInfoForIntent(Intent(hotspot), ResolveInfo().apply {
            activityInfo = ActivityInfo().apply { packageName = "com.android.settings"; name = "Hotspot" }
        })
    }

    @Test fun absentHotspotHandlerUsesOriginalWirelessSettingsAction() {
        L7HotspotSettings.openWifiSettings(activity)
        assertEquals(listOf(Settings.ACTION_WIRELESS_SETTINGS), activity.attempts.map { it.action })
        assertNull(activity.attempts.single().`package`)
        assertNull(activity.attempts.single().component)
    }

    @Test fun originalHotspotHandlerIsUsedWhenAvailable() {
        registerHotspot()
        activity.available = hotspot
        L7HotspotSettings.openWifiSettings(activity)
        assertEquals(listOf(hotspot), activity.attempts.map { it.action })
    }

    @Test fun rejectedHotspotHandlerFallsBackToOriginalWirelessSettings() {
        registerHotspot()
        L7HotspotSettings.openWifiSettings(activity)
        assertEquals(listOf(hotspot, Settings.ACTION_WIRELESS_SETTINGS), activity.attempts.map { it.action })
        assertNull(ShadowAlertDialog.getLatestAlertDialog())
    }

    @Test fun neitherOriginalEntryAvailableShowsFailureWithoutOpeningClientWifi() {
        activity.available = Settings.ACTION_WIFI_SETTINGS
        L7HotspotSettings.openWifiSettings(activity)
        assertEquals(listOf(Settings.ACTION_WIRELESS_SETTINGS), activity.attempts.map { it.action })
        assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing)
    }
}
