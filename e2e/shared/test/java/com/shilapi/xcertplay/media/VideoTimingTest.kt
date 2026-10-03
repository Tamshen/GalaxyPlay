package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class VideoTimingTest {
    @Test fun percentileUsesBucketUpperBoundAndIgnoresSingleTailOutlier() {
        val ages = FrameAgeHistogram()
        repeat(19) { ages.add(12_000_000) }
        ages.add(900_000_000)
        assertEquals(16L, ages.p95Millis())
        ages.clear()
        assertEquals(-1L, ages.p95Millis())
    }
    @Test fun timingIsBoundedAndDoesNotMatchAnOutputTwice() {
        val timings = VideoInputTiming(capacity = 2)
        timings.record(1, 100)
        timings.record(2, 110)
        timings.record(3, 120)
        assertEquals(-1L, timings.take(1, 200))
        assertEquals(90L, timings.take(2, 200))
        assertEquals(-1L, timings.take(2, 200))
        timings.clear()
        assertEquals(-1L, timings.take(3, 200))
    }
}
