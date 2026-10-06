package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class VideoInputStateTest {
    @Test fun slotReturnedAfterDeadlineIsReusedForTheNextFreshFrame() {
        val slot = VideoInputSlot()
        var now = 240_000_000L
        val index = slot.acquire { VideoInputPump.acquire(
            running = { true }, drain = {}, nanoTime = { now },
            timeoutNs = VideoFrameBudget.remainingNs(0, now),
            dequeue = { now += 20_000_000; 7 },
        ) }
        assertEquals(7, index)
        assertEquals(0L, VideoFrameBudget.remainingNs(0, now))
        assertEquals(7, slot.acquire { fail("取得的输入槽位不能遗失"); -1 })
        slot.queued()
        assertEquals(8, slot.acquire { 8 })
    }

    @Test fun absentSlotsAreRetriedAndReleaseDropsOwnership() {
        val slot = VideoInputSlot()
        assertEquals(-1, slot.acquire { -1 })
        assertEquals(3, slot.acquire { 3 })
        slot.clear()
        assertEquals(4, slot.acquire { 4 })
    }

    @Test fun transientWaitIsNotACodecFaultButSustainedWaitIs() {
        val progress = VideoInputProgress()
        assertFalse(progress.timedOut(0))
        assertFalse(progress.timedOut(1_999_999_999))
        assertTrue(progress.timedOut(2_000_000_000))
        progress.output()
        assertFalse(progress.timedOut(3_000_000_000))
        progress.reset()
        assertFalse(progress.timedOut(9_000_000_000))
    }
}
