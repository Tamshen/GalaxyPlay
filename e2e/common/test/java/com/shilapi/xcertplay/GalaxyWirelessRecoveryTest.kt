package com.shilapi.xcertplay

import com.shilapi.xcertplay.network.WirelessStartupFailure
import com.shilapi.xcertplay.network.WirelessStartupPolicy
import org.junit.Assert.*
import org.junit.Test

class GalaxyWirelessRecoveryTest {
    private val failure = WirelessStartupFailure.FIRST_TCP_TIMEOUT

    @Test fun typedAndBluetoothFailuresUseFastBoundedBackoffAndManualStopRejectsLateRetries() {
        val recovery = GalaxyWirelessRecovery()
        val delays = (0 until 5).map { generation ->
            (recovery.failed(generation, if (generation % 2 == 0) failure else null)
                as GalaxyWirelessRecovery.Decision.Retry).delayMillis
        }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 5_000L, 5_000L), delays)
        assertEquals(GalaxyWirelessRecovery.Decision.Stop, recovery.failed(5, null))
        recovery.manualRetry()
        recovery.stop()
        assertEquals(GalaxyWirelessRecovery.Decision.Ignore, recovery.failed(100, null))
        recovery.manualRetry()
        assertEquals(1_000L, (recovery.failed(101, failure) as GalaxyWirelessRecovery.Decision.Retry).delayMillis)
    }

    @Test fun duplicateFailureConsumesOneAttemptAndBudgetIsFinite() {
        val recovery = GalaxyWirelessRecovery()
        repeat(WirelessStartupPolicy.MAX_STARTUP_RETRIES) { generation ->
            assertTrue(recovery.failed(generation, failure) is GalaxyWirelessRecovery.Decision.Retry)
            assertEquals(GalaxyWirelessRecovery.Decision.Ignore, recovery.failed(generation, failure))
        }
        assertEquals(GalaxyWirelessRecovery.Decision.Stop, recovery.failed(100, failure))
        assertTrue(recovery.stopped)
        recovery.manualRetry()
        assertFalse(recovery.stopped)
        assertTrue(recovery.failed(100, failure) is GalaxyWirelessRecovery.Decision.Retry)
    }

    @Test fun badHotspotConfigurationStopsImmediatelyWithoutBlindRetries() {
        val recovery = GalaxyWirelessRecovery()
        assertEquals(GalaxyWirelessRecovery.Decision.Stop,
            recovery.failed(1, WirelessStartupFailure.HOTSPOT_CONFIGURATION))
        assertEquals(0, recovery.retries)
    }

    @Test fun firstFrameOrOldSessionCannotResetSpentBudget() {
        val recovery = GalaxyWirelessRecovery()
        val session = Any()
        recovery.failed(1, failure)
        assertTrue(recovery.firstFrame(session, 10))
        assertFalse(recovery.firstFrame(session, 20))
        assertFalse(recovery.stable(Any(), 10 + WirelessStartupPolicy.STABLE_SESSION_MILLIS))
        assertFalse(recovery.stable(session, 9 + WirelessStartupPolicy.STABLE_SESSION_MILLIS))
        assertEquals(1, recovery.retries)
        assertTrue(recovery.stable(session, 10 + WirelessStartupPolicy.STABLE_SESSION_MILLIS))
        assertEquals(0, recovery.retries)
        recovery.failed(2, failure)
        recovery.firstFrame(session, 100)
        recovery.disconnected()
        assertFalse(recovery.stable(session, 100 + WirelessStartupPolicy.STABLE_SESSION_MILLIS))
        assertEquals(1, recovery.retries)
    }
}
