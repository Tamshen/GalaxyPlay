package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class VideoStartupWatchdogTest {
    @Test fun noVideoOrTooFewFramesDoesNotPretendToBeDecoderFailure() {
        val watch = VideoStartupWatchdog(100)
        assertNull(watch.failure(999))
        watch.input(0)
        watch.input(1)
        assertNull(watch.failure(999))
    }

    @Test fun noOutputReportsOnceAndResetRequiresNewInputs() {
        val watch = VideoStartupWatchdog(100)
        repeat(3) { watch.input(it.toLong()) }
        assertNull(watch.failure(99))
        assertTrue(watch.failure(100)!!.startsWith("no decoded output"))
        assertNull(watch.failure(999))
        watch.reset()
        assertNull(watch.failure(999))
    }

    @Test fun outputWithoutPresentationIsDistinguishedFromHealthySurface() {
        val watch = VideoStartupWatchdog(100)
        repeat(3) { watch.input(it.toLong()) }
        watch.output()
        assertTrue(watch.failure(100)!!.startsWith("no Surface render callback"))
        watch.reset()
        repeat(3) { watch.input(it.toLong()) }
        watch.rendered()
        assertNull(watch.failure(999))
    }
}
