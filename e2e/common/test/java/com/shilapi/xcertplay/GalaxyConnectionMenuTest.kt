package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.os.Looper
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
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
    private var deferStop = false
    private var release: (() -> Unit)? = null

    @Before fun setup() {
        CarPlayBackgroundSession.clear()
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setTheme(R.style.Theme_Xcertplay)
        L7Agreement.accept(activity)
    }

    @After fun cleanup() {
        dialog?.dismiss()
        release?.invoke()
        shadowOf(Looper.getMainLooper()).idle()
        CarPlayBackgroundSession.clear()
        activity.finish()
    }

    private fun session(transport: CarPlayTransport = CarPlayTransport.WIRED): CarPlayController {
        val controller = mock(CarPlayController::class.java)
        `when`(controller.transport).thenReturn(transport)
        CarPlayBackgroundSession.store(controller, mock(AndroidMediaSink::class.java), 1440, 1920, Any()) {
            stopRequests++
            val completion = it
            val finish = { CarPlayBackgroundSession.clear(controller); completion() }
            if (deferStop) release = finish else finish()
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

    @Test fun confirmingReopensWiredHostOnceUsingActualTransport() {
        session()
        select()
        AirPlayPersistence.saveWirelessEnabled(activity, true)
        val confirm = dialog!!.getButton(AlertDialog.BUTTON_POSITIVE)
        confirm.performClick()
        confirm.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, stopRequests)
        assertFalse(AirPlayPersistence.loadWirelessEnabled(activity))
        val app = shadowOf(activity.application)
        assertEquals(CarPlayHostActivity::class.java.name, app.nextStartedActivity.component!!.className)
        assertNull(app.nextStartedActivity)
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
    @Test fun wirelessReconnectWaitsForReleaseAndKeepsActualTransport() {
        session(CarPlayTransport.WIRELESS)
        AirPlayPersistence.saveWirelessEnabled(activity, false)
        deferStop = true
        select()
        dialog!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, stopRequests)
        assertNull(shadowOf(activity.application).nextStartedActivity)
        assertFalse(AirPlayPersistence.loadWirelessEnabled(activity))
        release!!.invoke()
        release!!.invoke()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(AirPlayPersistence.loadWirelessEnabled(activity))
        val app = shadowOf(activity.application)
        assertEquals(CarPlayHostActivity::class.java.name, app.nextStartedActivity.component!!.className)
        assertNull(app.nextStartedActivity)
    }

    @Test fun anotherSessionCreatedDuringReleaseCannotBeReplacedByReconnect() {
        session()
        deferStop = true
        select()
        dialog!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val replacement = session()
        release!!.invoke()
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(replacement, CarPlayBackgroundSession.snapshot()!!.controller)
        assertNull(shadowOf(activity.application).nextStartedActivity)
    }

    @Test fun appExitDuringReleasePreventsHostReopening() {
        session()
        deferStop = true
        select()
        dialog!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        org.robolectric.util.ReflectionHelpers.setStaticField(L7AppExit::class.java, "exiting", true)
        try {
            release!!.invoke()
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(shadowOf(activity.application).nextStartedActivity)
        } finally {
            org.robolectric.util.ReflectionHelpers.setStaticField(L7AppExit::class.java, "exiting", false)
        }
    }

    @Test fun stoppingRetiresOldHostButApplicationCanOpenReplacementAfterRelease() {
        session()
        deferStop = true
        select()
        dialog!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        activity.finish()
        release!!.invoke()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(CarPlayHostActivity::class.java.name,
            shadowOf(activity.application).nextStartedActivity.component!!.className)
    }

}
