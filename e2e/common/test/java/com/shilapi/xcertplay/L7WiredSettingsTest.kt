package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.os.Looper
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class L7WiredSettingsTest {
    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private var settings: L7WiredSettings? = null
    private var connected = 0
    private fun create(inspect: () -> L7UsbReadiness): L7WiredSettings =
        L7WiredSettings(activity, LinearLayout(activity), { connected++ }, {}, inspect).also { settings = it }
    @After fun cleanup() { settings?.dispose() }
    private fun await(view: L7WiredSettings) {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (!view.connectButton.isEnabled && System.nanoTime() < deadline) {
            Thread.sleep(5); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        }
        assertTrue(view.connectButton.isEnabled)
    }

    @Test fun entryOnlyInspectsAndMissingDeviceRequiresExplicitWaitConfirmation() {
        val view = create { L7UsbReadiness(true) }; await(view)
        assertEquals(0, connected)
        view.connectButton.performClick(); await(view)
        assertEquals(0, connected)
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(dialog.isShowing)
        assertEquals(activity.getString(R.string.l7_usb_wait_connect), dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, connected)
    }

    @Test fun detectedDeviceWithPendingPermissionUsesExistingConnectionFlowOnlyOnClick() {
        val view = create { L7UsbReadiness(true, 1, 0) }; await(view)
        assertEquals(0, connected)
        view.connectButton.performClick(); await(view)
        assertEquals(1, connected)
    }

    @Test fun unsupportedHostCannotStartConnection() {
        val view = create { L7UsbReadiness(false) }; await(view)
        view.connectButton.performClick(); await(view)
        assertEquals(0, connected)
        assertEquals(activity.getString(R.string.l7_usb_refresh),
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
    }

    @Test fun backgroundDiscardsLateResultAndCannotStartConnection() {
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        var block = false
        val view = create {
            if (block) { entered.countDown(); release.await(2, TimeUnit.SECONDS) }
            L7UsbReadiness(true, 1, 1)
        }
        try {
            await(view); block = true
            view.connectButton.performClick()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            view.background(); release.countDown(); Thread.sleep(30)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, connected)
        } finally { release.countDown() }
    }
}
