package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30], manifest = Config.NONE)
class GalaxyVideoPerformanceTest {
    @Test fun performanceReadsDoNotConsumeTheOriginalDiagnosticWindow() {
        var time = 0L
        val stats = VideoStats(nanoTime = { time })
        stats.onReceived(100); stats.onReceived(100)
        stats.onInput(0); stats.onSubmitted(0, 0); stats.onPresented(0, 0)
        stats.onQueued(2, 200, 3)
        repeat(10) {
            val sample = stats.performance(110, "codec", 800, 480, 60)
            assertEquals(2L, sample.received); assertEquals(1L, sample.presented); assertEquals(3L, sample.dropped)
        }
        time = 5_000_000_000
        val line = stats.logIfDue()!!
        assertTrue(line.contains("rx=0.4fps")); assertTrue(line.contains("presented=0.2fps"))
        val after = stats.performance(110, null, 0, 0, 60)
        assertEquals(2L, after.received); assertEquals(1L, after.presented); assertEquals(3L, after.dropped)
        stats.onReceived(100)
        assertEquals(3L, stats.performance(110, null, 0, 0, 60).received)
        assertNotEquals(after.epoch, VideoStats().performance(110, null, 0, 0, 60).epoch)
    }
}
