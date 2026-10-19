package com.shilapi.xcertplay

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.shilapi.xcertplay.network.WirelessStartupFailure
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter.from
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en", manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class WirelessStartupRecoveryTest {
    private lateinit var activity: CarPlayHostActivity
    private lateinit var retry: Button
    private val failure = CarPlayStatus.Failed("timeout", startupFailure = WirelessStartupFailure.FIRST_TCP_TIMEOUT)

    @Before fun setup() {
        CarPlayBackgroundSession.clear()
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        ReflectionHelpers.setField(CarPlayBackgroundSession, "owner", activity)
        ReflectionHelpers.setField(activity, "wirelessEnabled", true)
        val panel = L7ConnectionPanel(activity, true, {}, {}, {})
        retry = panel.retryButton
        ReflectionHelpers.setField(activity, "connectionPanel", panel)
    }

    @After fun cleanup() {
        ReflectionHelpers.getField<Handler>(activity, "mainHandler").removeCallbacksAndMessages(null)
        CarPlayBackgroundSession.clear()
    }

    private fun report(generation: Int = 0, status: CarPlayStatus = failure) {
        ReflectionHelpers.callInstanceMethod<(CarPlayStatus) -> Unit>(activity, "createStatusReporter",
            from(Int::class.javaPrimitiveType, generation))(status)
    }
    private fun budget() = ReflectionHelpers.getField<GalaxyWirelessRecovery>(activity, "startupRecovery")

    @Test fun duplicateFailureAndCleanupErrorScheduleOnlyOneRetry() {
        report()
        report()
        report(status = CarPlayStatus.Failed("Bluetooth socket closed"))
        assertEquals(1, budget().retries)
        assertEquals(1, ReflectionHelpers.getField<Int>(activity, "reconnectAttempts"))
    }

    @Test fun waitingPageShowsNetworkRefreshAndFailureStepWithNextAttempt() {
        ReflectionHelpers.setField(activity, "wirelessEnabled", true)
        // common 库默认未启用产品 UI；与 mobile 的资源覆盖保持一致。
        ReflectionHelpers.setField(activity, "l7DebugLogs\$delegate", lazy { true })
        val stage = TextView(activity)
        ReflectionHelpers.setField(activity, "stageStatusView", stage)
        report(status = CarPlayStatus.WaitingForWirelessNetwork(1))
        assertTrue(stage.text.toString().contains("4/5"))
        assertTrue(stage.text.toString().contains("Waiting for the iPhone"))
        report(status = CarPlayStatus.WaitingForWirelessNetwork(2))
        assertTrue(stage.text.toString().contains("Requesting it again"))
        report()
        assertTrue(stage.text.toString().contains("4/5"))
        assertTrue(stage.text.toString().contains("attempt 2"))
        assertTrue(stage.text.toString().contains("1 s"))
    }

    @Test fun exhaustedBudgetStopsAutomaticRecoveryAndExposesRetry() {
        repeat(6) { generation ->
            ReflectionHelpers.setField(activity, "restartGeneration", generation)
            ReflectionHelpers.setField(activity, "reconnectScheduled", false)
            report(generation)
        }
        assertEquals(5, budget().retries)
        assertTrue(budget().stopped)
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        assertEquals(View.VISIBLE, retry.visibility)
        report(5, CarPlayStatus.Failed("Bluetooth socket closed"))
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
    }

    @Test fun unrecoverableConfigurationDoesNotAutomaticallyRetry() {
        report(status = CarPlayStatus.Failed("Permission missing", startupFailure = WirelessStartupFailure.HOTSPOT_CONFIGURATION))
        assertEquals(0, budget().retries)
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        assertEquals(View.VISIBLE, retry.visibility)
    }

    @Test fun repeatedUnclassifiedBluetoothFailureAlsoExhaustsTheBudget() {
        repeat(6) { generation ->
            ReflectionHelpers.setField(activity, "restartGeneration", generation)
            ReflectionHelpers.setField(activity, "reconnectScheduled", false)
            report(generation, CarPlayStatus.Failed("Bluetooth RFCOMM read failed"))
        }
        assertTrue(budget().stopped)
        assertEquals(5, budget().retries)
        assertEquals(View.VISIBLE, retry.visibility)
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
    }

    @Test fun wiredFailureKeepsItsExistingRetryPathWithoutUsingWirelessBudget() {
        ReflectionHelpers.setField(activity, "wirelessEnabled", false)
        report(status = CarPlayStatus.Failed("USB transport error"))
        assertEquals(0, budget().retries)
        assertTrue(ReflectionHelpers.getField(activity, "reconnectScheduled"))
    }

    @Test fun wiredModeFailureStopsQueuedRetryAndKeepsActionableFeedback() {
        ReflectionHelpers.setField(activity, "wirelessEnabled", false)
        val stage = TextView(activity)
        ReflectionHelpers.setField(activity, "stageStatusView", stage)
        report(status = CarPlayStatus.Failed("earlier USB error"))
        assertTrue(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        report(status = CarPlayStatus.Failed("CarPlay configuration request transferred -1 of 1 bytes",
            usbModeSwitchFailed = true))
        assertTrue(budget().stopped)
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        assertEquals(View.VISIBLE, retry.visibility)
        val stoppedText = stage.text.toString()
        assertTrue(stoppedText.contains("USB connection help"))
        report(status = CarPlayStatus.DiscoveringIphone)
        report(status = CarPlayStatus.Failed("late transport error"))
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(40))
        assertEquals(stoppedText, stage.text.toString())
        assertEquals(0, ReflectionHelpers.getField<Int>(activity, "restartGeneration"))
        assertEquals(1, ReflectionHelpers.getField<Int>(activity, "reconnectAttempts"))
    }

    @Test fun lateTransportErrorCannotReplaceExhaustedFeedbackWithGenericReconnectingText() {
        val stage = TextView(activity)
        ReflectionHelpers.setField(activity, "stageStatusView", stage)
        ReflectionHelpers.setField(activity, "l7DebugLogs\$delegate", lazy { true })
        repeat(6) { generation ->
            ReflectionHelpers.setField(activity, "restartGeneration", generation)
            ReflectionHelpers.setField(activity, "reconnectScheduled", false)
            report(generation)
        }
        val stoppedText = stage.text.toString()
        assertTrue(stoppedText.contains(activity.getString(com.shilapi.xcertplay.host.R.string.wireless_startup_retries_exhausted)))
        val listener = ReflectionHelpers.callInstanceMethod<com.shilapi.xcertplay.airplay.AirPlaySessionListener>(
            activity, "createSessionListener", from(Int::class.javaPrimitiveType, 5))
        listener.onTransportError("AirPlay session ended while closing")
        listener.onSessionEnded(org.mockito.Mockito.mock(com.shilapi.xcertplay.airplay.AirPlaySession::class.java))
        report(5, CarPlayStatus.RunningWireless)
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(stoppedText, stage.text.toString())
        assertEquals(View.VISIBLE, retry.visibility)
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
    }

    @Test fun staleGenerationUserDisconnectMenuAndDifferentOwnerCannotSchedule() {
        report(1)
        assertEquals(0, budget().retries)
        ReflectionHelpers.setField(activity, "menuOpen", true)
        report()
        assertEquals(0, budget().retries)
        ReflectionHelpers.setField(activity, "menuOpen", false)
        ReflectionHelpers.getField<AtomicBoolean>(activity, "shuttingDown").set(true)
        report()
        assertEquals(0, budget().retries)
        ReflectionHelpers.getField<AtomicBoolean>(activity, "shuttingDown").set(false)
        ReflectionHelpers.setField(CarPlayBackgroundSession, "owner", Any())
        report()
        assertEquals(0, budget().retries)
    }
}
