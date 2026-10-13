package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class AudioCodecInputWaitTest {
    @Test fun pendingOutputIsDrainedBeforeDroppingTheBurstPacket() {
        var available = false
        var drains = 0
        val wait = AudioCodecInputWait({ 0 }, { fail("排出输出后即可继续，不能忙等") })
        assertEquals(3, wait.await({ true }, { if (available) 3 else -1 }, { drains++; available = true }))
        assertEquals(1, drains)
        assertEquals(1L, wait.retries)
        assertEquals(0L, wait.timeouts)
    }
    @Test fun cpuSchedulingDelayPreservesInputWhileWaitingAndStopsAtTheBudget() {
        var time = 0L
        var sleeps = 0
        val wait = AudioCodecInputWait({ time }, { sleeps++; time += 2_000_000 })
        assertEquals(1, wait.await({ true }, { if (time >= 40_000_000) 1 else -1 }, {}))
        assertEquals(20, sleeps)
        assertEquals(0L, wait.timeouts)
        val start = time
        assertEquals(-1, wait.await({ true }, { -1 }, {}))
        assertEquals(100_000_000L, time - start)
        assertEquals(1L, wait.timeouts)
    }
    @Test fun cancellationDuringDrainDoesNotQueueAnOldPacketOrCountAFalseTimeout() {
        var active = true
        var dequeues = 0
        val wait = AudioCodecInputWait({ 0 }, { fail("已取消不能等待") })
        assertEquals(-1, wait.await({ active }, { dequeues++; -1 }, { active = false }))
        assertEquals(1, dequeues)
        assertEquals(0L, wait.timeouts)
    }
}
