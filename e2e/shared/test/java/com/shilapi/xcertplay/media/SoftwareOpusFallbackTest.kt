package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.AudioStreamId
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class SoftwareOpusFallbackTest {
    private fun await(condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < until) Thread.sleep(5)
        assertTrue("音频 worker 未达到预期状态", condition())
    }

    @Test fun absentSystemEncoderStillProducesDecodableMicrophonePacket() {
        assertTrue(AudioCodecCapabilities.opusCandidates(true).isEmpty())
        OpusEncoder(24_000).use { encoder ->
            assertTrue(encoder.available)
            val packet = encoder.encode(ByteArray(1_920)).single()
            SoftwareOpusDecoder(48_000, 1).use { decoder -> assertEquals(1_920, decoder.decode(packet)) }
        }
        AudioCodecCapabilities.rejectOpusEncoder()
        assertTrue(AudioCodecCapabilities.opusInputAvailable())
    }

    @Test fun absentSystemDecoderRendersShortOpusPacketsThroughExistingWorker() {
        assertTrue(AudioCodecCapabilities.opusCandidates(false).isEmpty())
        val logs = CopyOnWriteArrayList<String>()
        val sink = AndroidMediaSink(wirelessAudio = true, audioFocusEnabled = false, onAudioDiagnostic = logs::add)
        val id = AudioStreamId(101, "default")
        val format = AudioFormat(AudioCodecKind.OPUS, 48_000, 1, 101, "default")
        try {
            sink.onAudioStarted(id, format, 0)
            await { logs.any { it.contains("decoder ready") && it.contains("backend=SOFTWARE") } }
            await { logs.any { it.startsWith("Audio: ready") } }
            val renderer = ReflectionHelpers.getField<Map<AudioStreamId, Any>>(sink, "audioRenderers").getValue(id)
            val packet = ByteArray(15).apply { this[12] = 0xf8.toByte(); this[13] = 0xff.toByte(); this[14] = 0xfe.toByte() }
            repeat(12) { sink.onAudioRtp(id, format, packet.copyOf(), it * 960) }
            await { ReflectionHelpers.getField<Long>(renderer, "totalWrittenFrames") > 0L }
            assertTrue(AudioCodecCapabilities.opusOutputAvailable())
            assertFalse(logs.any { it.contains("skipping short packet") || it.contains("worker failed") })
            sink.close()
            await { ReflectionHelpers.getField<Any?>(renderer, "softwareOpus") == null }
        } finally { sink.close() }
    }
}
