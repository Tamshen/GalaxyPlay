package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class VideoRecoveryGateTest {
    @Test fun repeatedFailuresBackOffAndStopUntilExplicitReset() {
        val gate = VideoRecoveryGate()
        var now = 0L
        repeat(4) {
            assertTrue(gate.canRetry(now))
            gate.onFailure(now)
            assertFalse(gate.canRetry(now))
            now = gate.retryAtNs
        }
        assertTrue(gate.exhausted)
        assertFalse(gate.canRetry(Long.MAX_VALUE))
        gate.reset()
        assertTrue(gate.canRetry(now))
    }

    @Test fun aSingleSuccessfulFrameDoesNotRefillTheRecoveryBudget() {
        val gate = VideoRecoveryGate(stableNs = 100)
        gate.onFailure(0)
        gate.onOutput(300_000_000)
        gate.onOutput(300_000_050)
        assertEquals(1, gate.failures)
        gate.onFailure(300_000_060)
        assertEquals(2, gate.failures)
        gate.onOutput(900_000_000)
        gate.onOutput(900_000_100)
        assertEquals(0, gate.failures)
    }
}
