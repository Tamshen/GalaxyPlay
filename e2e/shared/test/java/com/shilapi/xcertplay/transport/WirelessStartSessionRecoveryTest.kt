package com.shilapi.xcertplay.transport

import org.junit.Assert.*
import org.junit.Test

class WirelessStartSessionRecoveryTest {
    private var now = 0L
    private var waiting = true
    private val recovery = WirelessStartSessionRecovery({ waiting }, nowNanos = { now * 1_000_000 })

    @Test fun neverRetriesBeforeASuccessfulStartAndWakesAtFiveSeconds() {
        assertEquals(30_000L, recovery.pollTimeout(30_000))
        assertFalse(recovery.retryIfDue { fail("no start request") })
        recovery.startSessionSent()
        assertEquals(5_000L, recovery.pollTimeout(30_000))
        now = 4_999
        assertEquals(1L, recovery.pollTimeout(30_000))
        assertFalse(recovery.retryIfDue { fail("too early") })
        now = 5_000
        var sends = 0
        assertTrue(recovery.retryIfDue { sends++ })
        now = 100_000
        assertFalse(recovery.retryIfDue { sends++ })
        assertEquals(1, sends)
        assertEquals(10L, recovery.pollTimeout(10))
    }

    @Test fun firstTcpCancellationOrOldGenerationPermanentlyStopsRefresh() {
        recovery.startSessionSent()
        waiting = false
        assertEquals(30_000L, recovery.pollTimeout(30_000))
        waiting = true
        now = 5_000
        assertFalse(recovery.retryIfDue { fail("stale refresh") })
    }

    @Test fun explicitCancellationAndOtherStartRequestConsumeTheExtraBudget() {
        for (explicit in listOf(true, false)) {
            val value = WirelessStartSessionRecovery({ true }, nowNanos = { now * 1_000_000 })
            now = 0; value.startSessionSent()
            now = 1_000
            if (explicit) value.cancel() else value.startSessionSent()
            now = 5_000
            assertFalse(value.retryIfDue { fail("duplicate refresh") })
        }
    }

    @Test fun originalDeadlineWinsAndInterruptedWorkerDoesNotSend() {
        recovery.startSessionSent()
        assertEquals(2_000L, recovery.pollTimeout(2_000))
        Thread.currentThread().interrupt()
        try {
            now = 5_000
            assertFalse(recovery.retryIfDue { fail("cancelled worker") })
        } finally { Thread.interrupted() }
    }

    @Test fun failedRefreshIsNotRetriedOrReentered() {
        recovery.startSessionSent(); now = 5_000
        assertThrows(java.io.IOException::class.java) {
            recovery.retryIfDue {
                assertFalse(recovery.retryIfDue { fail("reentered") })
                throw java.io.IOException("write failed")
            }
        }
        assertFalse(recovery.retryIfDue { fail("third request") })
    }
}
