package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Looper
import android.provider.Settings
import com.shilapi.xcertplay.host.R
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
        var available = Settings.ACTION_WIFI_SETTINGS
        override fun startActivity(intent: Intent) {
            attempts += intent
            if (intent.action != available) throw ActivityNotFoundException()
        }
    }
    private val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()

    @Test fun wifiEntryUsesTheFirmwareHandlerWithoutAnAndroidPackageRestriction() {
        L7HotspotSettings.openWifiSettings(activity)
        assertEquals(1, activity.attempts.size)
        assertEquals(Settings.ACTION_WIFI_SETTINGS, activity.attempts.single().action)
        assertNull(activity.attempts.single().`package`)
    }

    @Test fun missingNativeHotspotHasExplicitWifiRecovery() {
        L7HotspotSettings.openSettings(activity)
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(dialog.isShowing)
        assertEquals(activity.getString(R.string.l7_hotspot_wifi_settings), dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(Settings.ACTION_WIFI_SETTINGS, activity.attempts.last().action)
        assertNull(activity.attempts.last().`package`)
    }

    @Test fun wirelessSettingsFallbackAndNoHandlerBothGiveDeterministicResults() {
        activity.available = Settings.ACTION_WIRELESS_SETTINGS
        L7HotspotSettings.openWifiSettings(activity)
        assertEquals(listOf(Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS), activity.attempts.map { it.action })
        assertNull(ShadowAlertDialog.getLatestAlertDialog())
        activity.available = "unavailable"
        L7HotspotSettings.openWifiSettings(activity)
        assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing)
    }
}
