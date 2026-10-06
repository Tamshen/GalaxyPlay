package com.shilapi.xcertplay

import com.shilapi.xcertplay.network.WirelessStartupFailure
import com.shilapi.xcertplay.network.WirelessStartupPolicy
import org.junit.Assert.*
import org.junit.Test

class GalaxyWirelessRecoveryTest {
    private val failure = WirelessStartupFailure.FIRST_TCP_TIMEOUT

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
