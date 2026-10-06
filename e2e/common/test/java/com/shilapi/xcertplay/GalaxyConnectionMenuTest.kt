package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.os.Looper
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 确认、取消和会话替换直接验证停止动作，不启动手机连接。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30], qualifiers = "zh-rCN")
class GalaxyConnectionMenuTest {
    private lateinit var activity: Activity
    private var dialog: AlertDialog? = null
    private var settingsOpened = 0
    private var stopRequests = 0

    @Before fun setup() {
        CarPlayBackgroundSession.clear()
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setTheme(R.style.Theme_Xcertplay)
    }

    @After fun cleanup() {
        dialog?.dismiss()
        CarPlayBackgroundSession.clear()
        activity.finish()
    }

    private fun session(): CarPlayController {
        val controller = mock(CarPlayController::class.java)
        CarPlayBackgroundSession.store(controller, mock(AndroidMediaSink::class.java), 1440, 1920, Any()) {
            stopRequests++
            CarPlayBackgroundSession.clear(controller)
            it()
        }
        CarPlayBackgroundSession.active = true
        return controller
    }

    private fun select() {
        dialog = GalaxyConnectionMenu.select(activity) { settingsOpened++ }
    }

    @Test fun disconnectedOpensSettingsWithoutStartingOrStoppingSession() {
        select()
        assertNull(dialog)
        assertEquals(1, settingsOpened)
        assertEquals(0, stopRequests)
        assertFalse(CarPlayBackgroundSession.hasSession())
    }

    @Test fun waitingSessionOpensSettingsWithoutCancellingItsAttempt() {
        session()
        CarPlayBackgroundSession.active = false
        select()
        assertNull(dialog)
        assertEquals(1, settingsOpened)
        assertEquals(0, stopRequests)
        assertTrue(CarPlayBackgroundSession.hasSession())
    }

    @Test fun connectedRequiresConfirmationAndCancelPreservesSession() {
        session()
        select()
        assertTrue(dialog!!.isShowing)
        assertEquals(0, stopRequests)
        assertEquals(0, settingsOpened)
        dialog!!.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, stopRequests)
        assertTrue(CarPlayBackgroundSession.active)
    }

    @Test fun confirmingStopsCurrentSessionOnce() {
        session()
        select()
        val confirm = dialog!!.getButton(AlertDialog.BUTTON_POSITIVE)
        confirm.performClick()
        confirm.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, stopRequests)
        assertFalse(CarPlayBackgroundSession.hasSession())
        assertFalse(CarPlayBackgroundSession.active)
        assertEquals(0, settingsOpened)
    }

    @Test fun oldConfirmationCannotStopReplacementSession() {
        session()
        select()
        val replacement = session()
        dialog!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, stopRequests)
        assertSame(replacement, CarPlayBackgroundSession.snapshot()!!.controller)
        assertTrue(CarPlayBackgroundSession.active)
    }

    @Test fun sessionEndedWhileDialogOpenDoesNotStopOrOpenSettings() {
        session()
        select()
        CarPlayBackgroundSession.active = false
        dialog!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, stopRequests)
        assertEquals(0, settingsOpened)
    }
}
