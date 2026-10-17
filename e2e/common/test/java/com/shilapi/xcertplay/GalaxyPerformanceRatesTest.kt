package com.shilapi.xcertplay

import com.shilapi.xcertplay.media.GalaxyVideoPerformance
import org.junit.Assert.*
import org.junit.Test

class GalaxyPerformanceRatesTest {
    private val owner = Any()
    private fun video(epoch: Long = 1, rx: Long = 0, presented: Long = 0, stream: Int = 110) =
        GalaxyVideoPerformance(stream, epoch, rx, rx, rx, presented, 0, 0, 0, "codec", 1440, 1920, 60)
    private fun input(time: Long, cpu: Long? = 0, who: Any? = owner, frames: List<GalaxyVideoPerformance> = emptyList()) =
        GalaxyPerformanceInput(time, cpu, 1024, 2048, null, null, null, null, null, who, frames, emptyList())
    private fun GalaxyPerformanceRates.take(value: GalaxyPerformanceInput) = sample(value, false, 10, 10, 0, false)

    @Test fun singleCoreCpuMayExceed100AndMissingOrResetCountersStayUnknown() {
        val rates = GalaxyPerformanceRates()
        assertNull(rates.take(input(0, 100)).cpuCorePercent)
        assertEquals(150.0, rates.take(input(1000, 1600)).cpuCorePercent!!, 0.001)
        assertNull(rates.take(input(2000, null)).cpuCorePercent)
        assertNull(rates.take(input(3000, 10)).cpuCorePercent)
        assertNull(rates.take(input(3000, 20)).cpuCorePercent)
        assertNull(rates.take(input(4000, 1)).cpuCorePercent)
    }
    @Test fun negotiatedFpsIsNeverUsedAsActualPresentationRate() {
        val rates = GalaxyPerformanceRates()
        assertNull(rates.take(input(0, frames = listOf(video()))).main?.presentedFps)
        val receiving = rates.take(input(1000, frames = listOf(video(rx = 30))))
        assertEquals(30.0, receiving.main!!.receivedFps!!, 0.001)
        assertNull(receiving.main!!.presentedFps)
        val rendered = rates.take(input(2000, frames = listOf(video(rx = 60, presented = 24))))
        assertEquals(24.0, rendered.main!!.presentedFps!!, 0.001)
        assertEquals(0.0, rates.take(input(3000, frames = listOf(video(rx = 60, presented = 24)))).main!!.presentedFps!!, 0.001)
    }
    @Test fun replacementPhoneDecoderAndStreamEndCannotReuseOldFps() {
        val rates = GalaxyPerformanceRates()
        rates.take(input(0, frames = listOf(video(rx = 30, presented = 30))))
        val nextOwner = Any()
        val replacement = rates.take(input(1000, who = nextOwner, frames = listOf(video(rx = 60, presented = 60))))
        assertNull(replacement.main!!.presentedFps)
        val decoder = rates.take(input(2000, who = nextOwner, frames = listOf(video(epoch = 2, rx = 120, presented = 120))))
        assertNull(decoder.main!!.presentedFps)
        assertNull(rates.take(input(3000, who = null)).main)
        assertNull(replacement.input.owner)
    }
    @Test fun auxiliaryStreamCannotMasqueradeAsMainAndCountersCannotGoNegative() {
        val rates = GalaxyPerformanceRates()
        rates.take(input(0, frames = listOf(video(rx = 20, presented = 20, stream = 111))))
        val sample = rates.take(input(1000, frames = listOf(video(rx = 10, presented = 10, stream = 111))))
        assertNull(sample.main); assertNull(sample.video.single().presentedFps)
    }
}
