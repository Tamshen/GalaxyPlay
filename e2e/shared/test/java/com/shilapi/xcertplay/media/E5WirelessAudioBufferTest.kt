package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import org.junit.Assert.*
import org.junit.Test

class E5WirelessAudioBufferTest {
    private fun format(type: String, stream: Int = 100) = AudioFormat(AudioCodecKind.LPCM, 16_000, 1, stream, type)

    @Test fun onlyWirelessRealtimeDownlinksUseTheE5Target() {
        for (type in listOf("telephony", "speechrecognition", "alert", "default", "compatibility")) {
            assertTrue(type, E5WirelessAudioBuffer.applies(true, format(type)))
            assertFalse(type, E5WirelessAudioBuffer.applies(false, format(type)))
        }
        assertFalse(E5WirelessAudioBuffer.applies(true, format("media")))
        assertFalse(E5WirelessAudioBuffer.applies(true, format("default", 102)))
        assertFalse(E5WirelessAudioBuffer.applies(true, format("unknown")))
    }

    @Test fun lowRateVoiceDoesNotAcquireTheOldHalfSecondByteFloor() {
        val plan = E5WirelessAudioBuffer.plan(16_000, 1, 640)
        assertEquals(1280, plan.trackBufferBytes)
        assertEquals(2, plan.startBytes)
        assertEquals(320, E5WirelessAudioBuffer.plan(8000, 1, 320).trackBufferBytes / 2)
    }

    @Test fun minimumSystemCapacityWinsAndTheFirstWriteFits() {
        val plan = E5WirelessAudioBuffer.plan(16_000, 1, 4096)
        assertEquals(4096, plan.trackBufferBytes)
        assertEquals(2, MediaAudioBuffer.startBytesFor(plan.startBytes, 640, 2))
    }

    @Test fun firstPacketWaitsEightyMillisecondsAndABurstUsesTheSampleTimeline() {
        val clock = WirelessAudioPlayoutClock(16_000)
        assertEquals(80_000_000L, clock.dueNs(1000, 0))
        assertEquals(100_000_000L, clock.dueNs(1320, 5_000_000))
        assertEquals(120_000_000L, clock.dueNs(1640, 6_000_000))
    }

    @Test fun lateArrivalDoesNotAddAnotherEightyMilliseconds() {
        val clock = WirelessAudioPlayoutClock(16_000)
        clock.dueNs(1000, 0)
        assertEquals(100_000_000L, clock.dueNs(1320, 400_000_000))
    }

    @Test fun unsignedSampleWrapKeepsTheSameClock() {
        val clock = WirelessAudioPlayoutClock(16_000)
        clock.dueNs(0xffff_ff00.toInt(), 0)
        assertEquals(112_000_000L, clock.dueNs(256, 32_000_000))
    }

    @Test fun backwardOrDiscontinuousSamplesCannotCreateAnUnboundedWait() {
        val clock = WirelessAudioPlayoutClock(16_000)
        clock.dueNs(1000, 0)
        assertEquals(100_000_000L, clock.dueNs(900, 20_000_000))
        assertEquals(110_000_000L, clock.dueNs(160_000, 30_000_000))
    }
}
