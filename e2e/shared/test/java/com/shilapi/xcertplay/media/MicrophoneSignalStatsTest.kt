package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class MicrophoneSignalStatsTest {
    @Test fun silenceIsNotMisreportedAsUsableSpeech() {
        val stats = MicrophoneSignalStats()
        stats.add(ByteArray(8), 8)
        assertEquals(4L, stats.samples)
        assertEquals(100, stats.zeroPercent)
        assertEquals(0, stats.rms)
    }

    @Test fun signedPcmAndMinimumValueDoNotOverflow() {
        val stats = MicrophoneSignalStats()
        stats.add(byteArrayOf(0, 0, 0xff.toByte(), 0x7f, 0, 0x80.toByte()), 6)
        assertEquals(3L, stats.samples)
        assertEquals(33, stats.zeroPercent)
        assertEquals(32768, stats.peak)
        assertEquals(26754, stats.rms)
    }

    @Test fun partialSampleSurvivesReadAndWindowBoundary() {
        val stats = MicrophoneSignalStats()
        stats.add(byteArrayOf(0x34), 1)
        stats.resetWindow()
        stats.add(byteArrayOf(0x12), 1)
        assertEquals(1L, stats.samples)
        assertEquals(0x1234, stats.rms)
        stats.resetWindow()
        assertEquals(0L, stats.samples)
        assertEquals(0, stats.peak)
    }
}
