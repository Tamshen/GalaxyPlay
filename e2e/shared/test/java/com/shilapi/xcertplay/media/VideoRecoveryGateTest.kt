package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class VideoRecoveryGateTest {
    @Test fun fourFailuresLeaveOnlyTwoCooledProbesThenRequireManualRetry() {
        val gate = VideoRecoveryGate()
        var now = 0L
        repeat(4) {
            assertTrue(gate.beginAttempt(now))
            gate.onFailure(now)
            assertFalse(gate.canRetry(now))
            now = gate.retryAtNs
        }
        assertTrue(gate.exhausted)
        repeat(2) { probe ->
            assertFalse(gate.beginAttempt(now - 1))
            repeat(20) { assertTrue(gate.canRetry(now)) }
            assertEquals(probe, gate.probes)
            assertTrue(gate.beginAttempt(now))
            assertEquals(probe + 1, gate.probes)
            gate.onFailure(now)
            assertEquals(now + 15_000_000_000L, gate.retryAtNs)
            now = gate.retryAtNs
        }
        assertTrue(gate.terminal)
        assertFalse(gate.beginAttempt(Long.MAX_VALUE))
        gate.reset()
        assertTrue(gate.beginAttempt(now))
        assertEquals(0, gate.probes)
    }

    @Test fun successfulProbeKeepsDecodingUntilARealFailureOrStableReset() {
        val gate = VideoRecoveryGate(stableNs = 100, outputGapNs = 50)
        var now = 0L
        repeat(4) { gate.onFailure(now); now = gate.retryAtNs }
        assertTrue(gate.beginAttempt(now))
        gate.onOutput(now)
        gate.onOutput(now + 50)
        assertEquals(3, gate.failures)
        gate.onOutput(now + 100)
        assertEquals(0, gate.failures)
        assertEquals(0, gate.probes)
        assertTrue(gate.canRetry(now + 100))
    }

    @Test fun isolatedSuccessfulFramesCannotRefillTheBudget() {
        val gate = VideoRecoveryGate(stableNs = 100, outputGapNs = 50)
        gate.onFailure(0)
        gate.onOutput(300_000_000)
        gate.onOutput(300_000_150)
        assertEquals(1, gate.failures)
        gate.onOutput(300_000_200)
        gate.onOutput(300_000_250)
        assertEquals(0, gate.failures)
    }

    @Test fun anotherFaultRestartsTheStableWindow() {
        val gate = VideoRecoveryGate(stableNs = 100, outputGapNs = 100)
        gate.onFailure(0)
        gate.onOutput(300_000_000)
        gate.onOutput(300_000_050)
        gate.onFailure(300_000_060)
        assertEquals(2, gate.failures)
        gate.onOutput(900_000_000)
        gate.onOutput(900_000_100)
        assertEquals(0, gate.failures)
    }
}
