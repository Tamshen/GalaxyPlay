package com.shilapi.xcertplay

import android.os.Debug
import android.os.SystemClock
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30])
class GalaxyPerformanceReaderTest {
    @Test fun pssAndGcQueriesAreThrottledIndependentlyOfBasicSamples() {
        mockStatic(Debug::class.java).use { debug ->
            val reader = GalaxyPerformanceReader()
            repeat(10) { reader.read(false); ShadowSystemClock.advanceBy(Duration.ofSeconds(1)) }
            debug.verify({ Debug.getMemoryInfo(any(Debug.MemoryInfo::class.java)) }, times(1))
            reader.read(false)
            debug.verify({ Debug.getMemoryInfo(any(Debug.MemoryInfo::class.java)) }, times(2))
            debug.verify({ Debug.getRuntimeStat("art.gc.gc-count") }, times(2))
        }
    }
    @Test fun slowMemoryQueryDoesNotLeaveCpuAndVideoTimestampBeforeTheQuery() {
        mockStatic(Debug::class.java).use { debug ->
            debug.`when`<Unit> { Debug.getMemoryInfo(any(Debug.MemoryInfo::class.java)) }.thenAnswer {
                ShadowSystemClock.advanceBy(Duration.ofSeconds(2)); null
            }
            val before = SystemClock.elapsedRealtime()
            val input = GalaxyPerformanceReader().read(false)
            assertEquals(before + 2000, input.wallMs)
            assertEquals(0L, input.memoryAgeMs)
            assertNull(input.pssKiB)
        }
    }
}
