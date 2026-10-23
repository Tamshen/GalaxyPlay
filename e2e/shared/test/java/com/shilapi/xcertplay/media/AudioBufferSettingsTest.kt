package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AudioBufferHint
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import org.junit.Assert.*
import org.junit.Test

class AudioBufferSettingsTest {
    private val format = AudioFormat(AudioCodecKind.LPCM, 48_000, 2, 100, "media")

    @Test fun phoneMillisecondsTakePriorityAndSamplesRoundUp() {
        assertEquals(240, AudioBufferHint.parse(mapOf("audioLatencyMs" to 240L,
            "latencyMin" to 48_000, "latencyMax" to 96_000), 48_000)!!.minimumMillis)
        val samples = AudioBufferHint.parse(mapOf("latencyMin" to 3529, "latencyMax" to 7057), 44_100)!!
        assertEquals(81, samples.minimumMillis)
        assertEquals(161, samples.maximumMillis)
        assertEquals("latencySamples", samples.source)
    }

    @Test fun invalidAndUnboundedHintsCannotControlPlayback() {
        for (value in listOf(-1, 0, 2001, Long.MAX_VALUE, Double.NaN, Double.POSITIVE_INFINITY, 80.5, "80"))
            assertNull("value=$value", AudioBufferHint.parse(mapOf("audioLatencyMs" to value), 48_000))
        assertNull(AudioBufferHint.parse(mapOf("latencyMin" to 960, "latencyMax" to 480), 48_000))
        assertNull(AudioBufferHint.parse(mapOf("latencyMin" to 1, "latencyMax" to 96_001), 48_000))
        assertNull(AudioBufferHint.parse(mapOf("latencyMin" to 1), 0))
        assertEquals(80, AudioBufferHint.parse(mapOf("audioLatencyMs" to 0, "latencyMin" to 3840), 48_000)!!.minimumMillis)
    }

    @Test fun eachPurposeUsesItsOwnChoiceAndHint() {
        val settings = AudioBufferSettings(0, 32, 0, 500)
        val hint = AudioBufferHint.parse(mapOf("audioLatencyMs" to 160), 48_000)
        val negotiated = format.copy(bufferHint = hint)
        // 音乐层（DiPlay 原版）不跟随手机协商值：0 会落回出厂默认 300 毫秒。
        assertEquals(300, StreamAudioBuffer.resolve(settings, AudioChannel.MEDIA, negotiated, true)!!.targetMillis)
        assertEquals(32, StreamAudioBuffer.resolve(settings, AudioChannel.PHONE, negotiated, true)!!.targetMillis)
        assertEquals(160, StreamAudioBuffer.resolve(settings, AudioChannel.ASSISTANT, negotiated, true)!!.targetMillis)
        assertEquals(500, StreamAudioBuffer.resolve(settings, AudioChannel.NAVIGATION, negotiated, true)!!.targetMillis)
        assertEquals(300, StreamAudioBuffer.resolve(AudioBufferSettings(), AudioChannel.MEDIA, negotiated, true)!!.targetMillis)
        assertNull(StreamAudioBuffer.resolve(settings, AudioChannel.RINGTONE, negotiated, true))
    }

    @Test fun missingHintFallsBackWithoutChangingTheSavedSelection() {
        val settings = AudioBufferSettings(0, 0, 0, 0)
        assertEquals(300, StreamAudioBuffer.resolve(settings, AudioChannel.MEDIA, format, true)!!.targetMillis)
        assertEquals(80, StreamAudioBuffer.resolve(settings, AudioChannel.PHONE, format, true)!!.targetMillis)
        assertEquals(32, StreamAudioBuffer.resolve(settings, AudioChannel.PHONE, format, false)!!.targetMillis)
        assertEquals(0, settings.phoneMillis)
        assertNull(StreamAudioBuffer.resolve(AudioBufferSettings(phoneMillis = 999), AudioChannel.PHONE, format, true))
        val plan = MediaAudioBuffer.plan(true, 48_000, 2, 4096, 0)
        assertEquals(57_600, plan.startBytes)
        assertEquals(96_000, plan.trackBufferBytes)
    }

    @Test fun largeNegotiatedTargetAndTimestampWrapStayBounded() {
        val clock = WirelessAudioPlayoutClock(48_000, 2000)
        val now = 10_000_000_000L
        assertEquals(now + 2_000_000_000L, clock.dueNs(-480, now))
        assertEquals(now + 2_020_000_000L, clock.dueNs(480, now + 20_000_000L))
        assertEquals(now + 2_040_000_000L, clock.dueNs(1440, now + 40_000_000L))
    }
}
