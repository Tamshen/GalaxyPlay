package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class MusicJitterRecoveryTest {
    @Test fun observedFourHundredMillisecondBurstGapRaisesOnlyTheRuntimeRecoveryWaterline() {
        val recovery = MusicJitterRecovery()
        val start = 57_600 // 300 ms，48 kHz 双声道。
        assertEquals(start, recovery.threshold(start, 96_000, 192_000, 4))
        recovery.observe(400)
        assertEquals(86_400, recovery.threshold(start, 96_000, 192_000, 4))
        recovery.observe(20)
        assertEquals(400L, recovery.gapMillis)
    }
    @Test fun longOutageCannotGrowBeyondGrantedCapacityOrLowerAnExplicitLargerBuffer() {
        val recovery = MusicJitterRecovery()
        recovery.observe(60_000)
        val threshold = recovery.threshold(57_600, 96_000, 192_000, 4)
        assertEquals(93_952, threshold)
        assertTrue(threshold + 2048 <= 96_000)
        val larger = MusicJitterRecovery().apply { observe(400) }
        assertEquals(192_000, larger.threshold(192_000, 230_400, 192_000, 4))
        recovery.observe(-1)
        assertEquals(60_000L, recovery.gapMillis)
        assertEquals(57_600, recovery.threshold(57_600, 0, 192_000, 4))
    }
    @Test fun existingQueueDoesNotPreventWirelessMusicFromRefillingAfterAnUnderrun() {
        val buffer = AudioBufferProgress(4)
        buffer.written(4000)
        assertTrue(buffer.shouldRebuffer(true, true, true, false, 900, 600, recoverOnQueuedBurst = true))
        assertFalse(buffer.shouldRebuffer(true, true, false, false, 900, 600, recoverOnQueuedBurst = true))
        assertFalse(buffer.shouldRebuffer(false, true, true, false, 900, 600, recoverOnQueuedBurst = true))
    }
}
