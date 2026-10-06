package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class VideoPlaybackWatchdogTest {
    @Test fun continuousExpiredInputsAreVisibleEvenWithoutSubmittedFrames() {
        val watch = VideoPlaybackWatchdog(timeoutNs = 100, arrivalGapNs = 50)
        listOf(0L, 25, 50, 75, 100).forEach(watch::received)
        assertTrue(watch.failure(100))
        assertFalse(watch.failure(110))
        watch.presented(110)
        listOf(125L, 150, 175, 200, 210).forEach(watch::received)
        assertTrue(watch.failure(210))
    }

    @Test fun noVideoStaticScreenAndRadioGapDoNotReportFalseFailures() {
        val watch = VideoPlaybackWatchdog(timeoutNs = 100, arrivalGapNs = 50)
        assertFalse(watch.failure(999))
        watch.received(0)
        watch.received(25)
        assertFalse(watch.failure(100))
        watch.received(1000)
        watch.received(1025)
        watch.received(1050)
        assertFalse(watch.failure(1050))
        watch.presented(1075)
        watch.received(1075)
        assertFalse(watch.failure(1100))
        assertFalse(watch.failure(2000))
    }

    @Test fun rebuildingAndTargetChangesRequireNewVideoEvidence() {
        val watch = VideoPlaybackWatchdog(timeoutNs = 100, arrivalGapNs = 100)
        repeat(3) { watch.received(it.toLong()) }
        watch.ready()
        assertFalse(watch.failure(1000))
        watch.received(1000)
        watch.received(1050)
        watch.received(1100)
        assertTrue(watch.failure(1100))
    }
}
