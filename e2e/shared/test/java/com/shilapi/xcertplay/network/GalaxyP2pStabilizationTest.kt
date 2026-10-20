package com.shilapi.xcertplay.network

import java.io.InterruptedIOException
import org.junit.Assert.*
import org.junit.Test

class GalaxyP2pStabilizationTest {
    @Test fun stabilizationEndsAtTwoSecondsAndNeverClaimsPhoneReception() {
        var now = 0L
        val logs = mutableListOf<String>()
        GalaxyP2pStabilization.await({ false }, logs::add, { now }) { now += it * 1_000_000 }
        assertEquals(2_000_000_000L, now)
        assertTrue(logs.last().contains("elapsedMs=2000"))
        assertTrue(logs.all { it.contains("airBroadcast=UNVERIFIED") })
    }

    @Test fun cancellationStopsTheWindowWithinOnePollingInterval() {
        var now = 0L
        val logs = mutableListOf<String>()
        assertThrows(InterruptedIOException::class.java) {
            GalaxyP2pStabilization.await({ now >= 100_000_000 }, logs::add, { now }) { now += it * 1_000_000 }
        }
        assertEquals(100_000_000L, now)
        assertFalse(logs.any { "stage=STABILIZED" in it })
    }

    @Test fun unavailableDiagnosticsCannotChangePreparation() {
        var now = 0L
        GalaxyP2pStabilization.await({ false }, { error("sink closed") }, { now }) { now += it * 1_000_000 }
        assertEquals(2_000_000_000L, now)
    }
}
