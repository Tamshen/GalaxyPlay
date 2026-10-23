package com.shilapi.xcertplay.media

import android.media.AudioTrack
import com.shilapi.xcertplay.airplay.AudioBufferHint
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.AudioStreamId
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30], manifest = Config.NONE)
class AudioBufferSelectionRendererTest {
    private fun await(condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < until) Thread.sleep(5)
        assertTrue("音频 worker 未达到预期状态", condition())
    }

    @Test fun compatibilityAudioMappedToMusicUsesTheSelectedMusicTarget() {
        val logs = CopyOnWriteArrayList<String>()
        val sink = AndroidMediaSink(wirelessAudio = true, advancedAudioChannelMapping = true,
            audioFocusEnabled = false,
            // 音乐水位只由 mediaBufferMillis 决定（DiPlay 原版语义），设置快照不再改写它。
            mediaBufferMillis = 500,
            platformAdaptation = object : MediaPlatformAdaptation { override val buffers = AudioBufferSettings(500) },
            onAudioDiagnostic = logs::add)
        try {
            val id = AudioStreamId(100, "compatibility")
            sink.onAudioStarted(id, AudioFormat(AudioCodecKind.LPCM, 48_000, 2, 100, id.audioType), 0)
            await { logs.any { it.startsWith("Audio: ready") } }
            assertTrue(logs.any { it.contains("jitterTargetMs=0") && it.contains("startMs=500") })
        } finally { sink.close() }
    }

    @Test fun concurrentPurposesUseIndependentBuffersWithoutDoublePrebuffer() {
        for (wireless in listOf(false, true)) {
            val logs = CopyOnWriteArrayList<String>()
            val sink = AndroidMediaSink(wirelessAudio = wireless, audioFocusEnabled = false,
                platformAdaptation = object : MediaPlatformAdaptation {
                    override val buffers = AudioBufferSettings(0, 32, 0, 500)
                }, onAudioDiagnostic = logs::add)
            try {
                val targets = mapOf("media" to 240, "telephony" to 32, "speechrecognition" to 160, "default" to 500)
                for ((purpose, _) in targets) {
                    val hint = AudioBufferHint.parse(mapOf("audioLatencyMs" to if (purpose == "media") 240 else 160), 48_000)
                    sink.onAudioStarted(AudioStreamId(100, purpose), AudioFormat(AudioCodecKind.LPCM,
                        48_000, 2, 100, purpose, bufferHint = hint), 0)
                }
                await { logs.count { it.startsWith("Audio: ready") } == 4 }
                val renderers = ReflectionHelpers.getField<Map<AudioStreamId, Any>>(sink, "audioRenderers")
                for ((purpose, target) in targets) {
                    val renderer = renderers.getValue(AudioStreamId(100, purpose))
                    val clock = ReflectionHelpers.getField<WirelessAudioPlayoutClock?>(renderer, "playoutClock")
                    val threshold = ReflectionHelpers.getField<Int>(renderer, "startThresholdBytes")
                    if (purpose == "media") {
                        assertNull(clock)
                        // 音乐层只按 DiPlay 的 300 毫秒出厂值起播，不再跟随手机上报的 240 毫秒。
                        assertEquals(57_600, threshold)
                    } else {
                        assertEquals(target, clock!!.targetMillis)
                        assertEquals(4, threshold)
                    }
                }
                assertFalse(logs.any { "renderer failed" in it })
            } finally { sink.close() }
        }
    }

    @Test fun manualMusicTargetDoesNotGrowAfterAnObservedNetworkGap() {
        val logs = CopyOnWriteArrayList<String>()
        val sink = AndroidMediaSink(wirelessAudio = true, audioFocusEnabled = false,
            platformAdaptation = object : MediaPlatformAdaptation { override val buffers = AudioBufferSettings(300) },
            onAudioDiagnostic = logs::add)
        try {
            val id = AudioStreamId(102, "media")
            sink.onAudioStarted(id, AudioFormat(AudioCodecKind.LPCM, 48_000, 2, 102, "media",
                bufferHint = AudioBufferHint.parse(mapOf("audioLatencyMs" to 900), 48_000)), 0)
            await { logs.any { it.startsWith("Audio: ready") } }
            val renderer = ReflectionHelpers.getField<Map<AudioStreamId, Any>>(sink, "audioRenderers").getValue(id)
            val jitter = ReflectionHelpers.getField<MusicJitterRecovery>(renderer, "musicJitter")
            jitter.observe(1000)
            Thread.sleep(100)
            assertEquals(57_600, ReflectionHelpers.getField<Int>(renderer, "startThresholdBytes"))
            assertFalse(logs.any { "jitter recovery startMs" in it })
            val track = ReflectionHelpers.getField<AudioTrack>(renderer, "track")
            assertNotEquals(AudioTrack.PLAYSTATE_PLAYING, track.playState)
        } finally { sink.close() }
    }
}
