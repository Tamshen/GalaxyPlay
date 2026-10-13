package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class GalaxyProcessPerformanceTest {
    @Test fun busyLoggingReadsProcessCountersOnlyOncePerFiveSeconds() {
        var time = 0L
        var cpu = 100L
        var reads = 0
        val sample = GalaxyProcessPerformance({ time }, { reads++; cpu }, { 3 * 1024 * 1024L })
        assertNull(sample.poll())
        repeat(10_000) { time = 4999; assertNull(sample.poll()) }
        assertEquals(1, reads)
        time = 5000; cpu = 1600
        assertEquals("PERFORMANCE elapsedMs=5000 processCpuMs=1500 cpuCorePercent=30 heapUsedMiB=3", sample.poll())
        assertEquals(2, reads)
    }

    @Test fun multipleCoresAndClockResetAreNotMisreportedAsWholeDeviceUsage() {
        var time = 1000L
        var cpu = 10L
        val sample = GalaxyProcessPerformance({ time }, { cpu }, { 0L })
        assertNull(sample.poll())
        time += 5000; cpu += 7500
        assertTrue(sample.poll()!!.contains("cpuCorePercent=150"))
        time = 0; cpu = 0
        assertNull(sample.poll())
        time = 5000; cpu = 100
        assertTrue(sample.poll()!!.contains("processCpuMs=100"))
    }
}
