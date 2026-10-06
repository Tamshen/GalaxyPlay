package com.shilapi.xcertplay

import android.os.Looper
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

/** 模拟旧会话延迟释放，验证真实入口的顺序和传输方式，不启动手机连接。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@LooperMode(LooperMode.Mode.PAUSED)
class L7ConnectionReconnectTest {
    private lateinit var activity: GalaxySettingsActivity
    private val controller = mock(CarPlayController::class.java)
    private var stopped: (() -> Unit)? = null
    private var stopRequests = 0

    @Before fun setup() {
        CarPlayBackgroundSession.clear()
        activity = Robolectric.buildActivity(GalaxySettingsActivity::class.java).get()
        activity.setTheme(R.style.Theme_Xcertplay)
        L7Agreement.accept(activity)
        // 无线用合成配置隔离热点操作；用例只验证旧会话停止和宿主重新打开。
        AirPlayPersistence.saveWirelessHotspotMode(activity, WirelessHotspotMode.WIFI_P2P)
        DiPlayPreferences.savePhone(activity, "02:00:00:00:00:01", "test phone")
    }

    @After fun cleanup() {
        CarPlayBackgroundSession.clear()
        ReflectionHelpers.setStaticField(CarPlayBackgroundSession::class.java, "stopping", false)
        ReflectionHelpers.getStaticField<MutableList<() -> Unit>>(CarPlayBackgroundSession::class.java, "stopWaiters").clear()
        val task = ReflectionHelpers.getField<Lazy<L7HotspotTask>>(activity, "hotspotTask\$delegate")
        if (task.isInitialized()) task.value.close()
        ReflectionHelpers.getField<android.os.Handler>(activity, "handler").removeCallbacksAndMessages(null)
    }

    private fun session(transport: CarPlayTransport) {
        `when`(controller.transport).thenReturn(transport)
        CarPlayBackgroundSession.store(controller, mock(AndroidMediaSink::class.java), 1440, 1920, Any()) { completion ->
            stopRequests++
            stopped = completion
        }
        CarPlayBackgroundSession.active = true
    }

    private fun row(): L7SettingRow {
        activity.javaClass.getDeclaredMethod("connectionChoicesL7", LinearLayout::class.java)
            .apply { isAccessible = true }.invoke(activity, LinearLayout(activity))
        refresh()
        return ReflectionHelpers.getField(activity, "reconnectRow")
    }

    private fun refresh() = activity.javaClass.getDeclaredMethod("refreshStatus")
        .apply { isAccessible = true }.invoke(activity)

    private fun completeStop() {
        CarPlayBackgroundSession.clear()
        stopped!!.invoke()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun usbSessionReconnectsOverUsbEvenWhenSavedPreferenceIsWireless() {
        session(CarPlayTransport.WIRED)
        AirPlayPersistence.saveWirelessEnabled(activity, true)
        row().performClick()
        assertEquals(1, stopRequests)
        assertNull(shadowOf(activity).nextStartedActivity)
        completeStop()
        assertFalse(AirPlayPersistence.loadWirelessEnabled(activity))
        assertEquals(CarPlayHostActivity::class.java.name, shadowOf(activity).nextStartedActivity.component!!.className)
    }

    @Test fun wirelessSessionReconnectsOverWirelessEvenWhenSavedPreferenceIsUsb() {
        session(CarPlayTransport.WIRELESS)
        AirPlayPersistence.saveWirelessEnabled(activity, false)
        row().performClick()
        assertEquals(1, stopRequests)
        assertNull(shadowOf(activity).nextStartedActivity)
        completeStop()
        assertTrue(AirPlayPersistence.loadWirelessEnabled(activity))
        assertEquals(CarPlayHostActivity::class.java.name, shadowOf(activity).nextStartedActivity.component!!.className)
    }

    @Test fun repeatedClicksWhileStoppingDoNotStartAnotherReconnect() {
        session(CarPlayTransport.WIRED)
        val row = row()
        row.performClick()
        assertFalse(row.isEnabled)
        row.performClick()
        assertEquals(1, stopRequests)
        completeStop()
        assertNotNull(shadowOf(activity).nextStartedActivity)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun noSessionLeavesReconnectDisabledWithAnExplanation() {
        val row = row()
        assertFalse(row.isEnabled)
        assertEquals(activity.getString(R.string.l7_reconnect_no_session), row.feedbackView.text.toString())
        row.performClick()
        assertEquals(0, stopRequests)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun closedControllerCannotBeReconnected() {
        session(CarPlayTransport.WIRED)
        `when`(controller.isClosed()).thenReturn(true)
        assertFalse(row().isEnabled)
        assertEquals(0, stopRequests)
    }

    @Test fun anotherStopInProgressBlocksTheReconnectEntry() {
        session(CarPlayTransport.WIRED)
        CarPlayBackgroundSession.stop()
        assertFalse(row().isEnabled)
        assertEquals(1, stopRequests)
        completeStop()
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun finishingSettingsBeforeStopCompletesCannotReopenTheHost() {
        session(CarPlayTransport.WIRED)
        row().performClick()
        activity.finish()
        completeStop()
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun appExitBeforeStopCompletesCannotReopenTheHost() {
        session(CarPlayTransport.WIRED)
        row().performClick()
        ReflectionHelpers.setStaticField(L7AppExit::class.java, "exiting", true)
        try {
            completeStop()
            assertNull(shadowOf(activity).nextStartedActivity)
        } finally {
            ReflectionHelpers.setStaticField(L7AppExit::class.java, "exiting", false)
        }
    }
}
