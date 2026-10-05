package com.shilapi.xcertplay

import android.os.Looper
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class L7BluetoothMediaGuardTest {
    @Test fun diagnosticWaitsIdentifySupersessionPauseAndCloseWithoutReplaying() {
        val guard = guard()
        guard.start(); idle()
        val drops = mutableListOf<String>()
        var sent = 0
        guard.beforePlay({ sent++ }, drops::add); idle()
        guard.beforePlay({ sent++ }, drops::add); idle()
        guard.cancelPendingPlay(); idle()
        guard.beforePlay({ sent++ }, drops::add); idle()
        guard.close(); idle()
        assertEquals(listOf("SUPERSEDED_PLAY", "CANCELLED_BY_PAUSE", "BLUETOOTH_CLOSED"), drops)
        assertEquals(0, sent)
    }

    private val statuses = mutableListOf<BluetoothMediaStatus>()
    private val port = Port()

    @Test fun waitsForRealDisconnectAndOnlyTargetsSessionPhone() {
        val guard = guard()
        guard.start(); idle()
        assertEquals(listOf(TARGET), port.disconnected)
        assertEquals(BluetoothMediaStatus.DISCONNECTING, statuses.last())
        port.connected = false; port.changed(); idle()
        assertEquals(BluetoothMediaStatus.CLEAR, statuses.last())
        guard.close()
    }

    @Test fun unknownPhoneAndDisabledAutomationNeverDisconnect() {
        val unknown = guard(null)
        unknown.start(); idle()
        assertEquals(BluetoothMediaStatus.NO_TARGET, statuses.last())
        assertEquals(0, port.opens)
        unknown.close()
        val manual = guard(automatic = false)
        manual.start(); idle()
        assertEquals(BluetoothMediaStatus.CONFLICT, statuses.last())
        assertTrue(port.disconnected.isEmpty())
        manual.close()
    }

    @Test fun rejectionAndUnconfirmedRequestFallBackWithoutRetryLoop() {
        port.accepted = false
        val rejected = guard()
        rejected.start(); idle()
        assertEquals(BluetoothMediaStatus.BLOCKED, statuses.last())
        rejected.close()
        port.accepted = true
        val unconfirmed = guard()
        unconfirmed.start(); idle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(7))
        assertEquals(BluetoothMediaStatus.BLOCKED, statuses.last())
        assertEquals(2, port.disconnected.size)
        unconfirmed.close()
    }

    @Test fun reconnectionBudgetAndClosedCallbacksCannotDisconnectAgain() {
        val guard = guard()
        guard.start(); idle()
        repeat(2) {
            port.connected = false; port.changed(); idle()
            port.connected = true; port.changed(); idle()
        }
        assertEquals(2, port.disconnected.size)
        assertEquals(BluetoothMediaStatus.LIMIT, statuses.last())
        guard.close()
        port.changed(); idle()
        assertEquals(BluetoothMediaStatus.CLOSED, statuses.last())
        assertEquals(2, port.disconnected.size)
    }

    @Test fun unavailableProfileTimesOutAndLateReadyIsIgnored() {
        port.autoReady = false
        val guard = guard()
        guard.start(); idle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(7))
        assertEquals(BluetoothMediaStatus.BLOCKED, statuses.last())
        port.ready(); idle()
        assertTrue(port.disconnected.isEmpty())
        assertEquals(BluetoothMediaStatus.BLOCKED, statuses.last())
        guard.close()
    }

    @Test fun platformPermissionRejectionStopsAutomation() {
        port.permissionRejected = true
        val guard = guard()
        guard.start(); idle()
        assertEquals(BluetoothMediaStatus.BLOCKED, statuses.last())
        port.changed(); idle()
        assertEquals(1, port.disconnected.size)
        guard.close()
    }

    @Test fun explicitPlayWaitsForConfirmedDisconnectAndRepeatedClicksCoalesce() {
        val guard = guard()
        guard.start(); idle()
        var plays = 0
        guard.beforePlay { plays += 100 }
        guard.beforePlay { plays++ }; idle()
        assertEquals(0, plays)
        port.connected = false; port.changed(); idle()
        assertEquals(1, plays)
        // 已确认释放时，新播放不能因状态未变化而永久留在等待队列。
        guard.beforePlay { plays++ }; idle()
        assertEquals(2, plays)
        guard.close()
    }

    @Test fun pauseAndSessionCloseDiscardPendingPlay() {
        val guard = guard()
        guard.start(); idle()
        var plays = 0
        guard.beforePlay { plays++ }
        guard.cancelPendingPlay()
        port.connected = false; port.changed(); idle()
        assertEquals(0, plays)
        port.connected = true; port.changed(); idle()
        guard.beforePlay { plays++ }
        guard.close()
        port.connected = false; port.changed(); idle()
        assertEquals(0, plays)
    }

    @Test fun rejectedDisconnectAndUnknownTargetDoNotBlockExplicitPlayForever() {
        port.accepted = false
        val rejected = guard()
        rejected.start(); idle()
        var plays = 0
        rejected.beforePlay { plays++ }; idle()
        assertEquals(1, plays)
        rejected.close()
        val unknown = guard(null)
        unknown.start(); idle()
        unknown.beforePlay { plays++ }; idle()
        assertEquals(2, plays)
        unknown.close()
    }

    @Test fun pendingPlayFallsBackOnceAfterDisconnectConfirmationTimesOut() {
        val guard = guard()
        guard.start(); idle()
        var plays = 0
        guard.beforePlay { plays++ }; idle()
        assertEquals(0, plays)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
        assertEquals(BluetoothMediaStatus.BLOCKED, statuses.last())
        assertEquals(1, plays)
        port.changed(); idle()
        assertEquals(1, plays)
        guard.close()
    }

    @Test fun enablingDuringSessionHandlesExistingTargetWithoutInventingPlay() {
        val guard = guard(automatic = false)
        guard.start(); idle()
        assertTrue(port.disconnected.isEmpty())
        guard.setAutomatic(true); idle()
        assertEquals(listOf(TARGET), port.disconnected)
        assertEquals(BluetoothMediaStatus.DISCONNECTING, statuses.last())
        port.connected = false; port.changed(); idle()
        assertEquals(BluetoothMediaStatus.CLEAR, statuses.last())
        guard.close()
    }

    @Test fun disablingReleasesOnlyExplicitPendingPlayAndInvalidatesOldConfirmation() {
        val guard = guard()
        guard.start(); idle()
        var plays = 0
        guard.beforePlay { plays++ }; idle()
        assertEquals(0, plays)
        guard.setAutomatic(false); idle()
        assertEquals(1, plays)
        assertEquals(BluetoothMediaStatus.CONFLICT, statuses.last())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
        assertEquals(BluetoothMediaStatus.CONFLICT, statuses.last())
        port.changed(); idle()
        assertEquals(1, port.disconnected.size)
        assertEquals(1, plays)
        guard.close()
    }

    @Test fun quickTogglesKeepBudgetAndOldTimerCannotFailNewAttempt() {
        val guard = guard()
        guard.start(); idle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        guard.setAutomatic(false); guard.setAutomatic(true); idle()
        assertEquals(2, port.disconnected.size)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(BluetoothMediaStatus.DISCONNECTING, statuses.last())
        guard.setAutomatic(false); guard.setAutomatic(true); idle()
        assertEquals(BluetoothMediaStatus.LIMIT, statuses.last())
        assertEquals(2, port.disconnected.size)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
        assertEquals(BluetoothMediaStatus.LIMIT, statuses.last())
        guard.close()
    }

    @Test fun pauseBeforeDisablingDoesNotRestorePlayAndClosedOrFailedGuardCannotRestart() {
        val guard = guard()
        guard.start(); idle()
        var plays = 0
        guard.beforePlay { plays++ }; guard.cancelPendingPlay()
        guard.setAutomatic(false); idle()
        assertEquals(0, plays)
        guard.close()
        guard.setAutomatic(true); port.ready(); idle()
        assertEquals(1, port.disconnected.size)
        assertEquals(BluetoothMediaStatus.CLOSED, statuses.last())
        port.accepted = false
        val failed = guard()
        failed.start(); idle()
        val attempts = port.disconnected.size
        failed.setAutomatic(false); failed.setAutomatic(true); idle()
        assertEquals(attempts, port.disconnected.size)
        assertEquals(BluetoothMediaStatus.BLOCKED, statuses.last())
        failed.close()
    }

    private fun guard(address: String? = TARGET, automatic: Boolean = true) =
        L7BluetoothMediaGuard(address, automatic, port, statuses::add)
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private class Port : L7BluetoothMediaPort {
        var opens = 0
        var autoReady = true
        var connected = true
        var accepted = true
        var permissionRejected = false
        var ready: () -> Unit = {}
        var changed: () -> Unit = {}
        val disconnected = mutableListOf<String>()
        override fun open(ready: () -> Unit, changed: () -> Unit): Boolean {
            opens++; this.ready = ready; this.changed = changed
            if (autoReady) ready()
            return true
        }
        override fun state(address: String): BluetoothMediaState {
            assertEquals(TARGET, address)
            return BluetoothMediaState(connected, true)
        }
        override fun disconnect(address: String): Boolean {
            disconnected += address
            if (permissionRejected) throw SecurityException("test_permission_rejected")
            return accepted
        }
        override fun close() = Unit
    }

    companion object { private const val TARGET = "02:00:00:00:00:01" }
}
