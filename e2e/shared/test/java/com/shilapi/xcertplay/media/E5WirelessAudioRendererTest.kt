package com.shilapi.xcertplay.media

import android.media.AudioTrack
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

/** 经实际 sink、RTP 队列及写入路径检查小缓冲能启动，不依赖参数函数的同义断言。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class E5WirelessAudioRendererTest {
    private fun await(condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < until) Thread.sleep(5)
        assertTrue("音频 worker 未达到预期状态", condition())
    }

    @Test fun stoppedCallRendererIsReleasedBeforeMusicRecoveryStatistics() {
        val logs = CopyOnWriteArrayList<String>()
        val sink = AndroidMediaSink(wirelessAudio = true, audioFocusEnabled = false, onAudioDiagnostic = logs::add)
        try {
            val media = AudioStreamId(102, "media")
            val phone = AudioStreamId(100, "telephony")
            sink.onAudioStarted(media, AudioFormat(AudioCodecKind.LPCM, 48_000, 2, 102, "media"), 0)
            sink.onAudioStarted(phone, AudioFormat(AudioCodecKind.LPCM, 16_000, 1, 100, "telephony"), 0)
            await { logs.count { it.startsWith("Audio: ready") } == 2 }
            sink.onAudioStopped(phone)
            await { logs.any { it.contains("callLifecycle") && it.contains("stage=RELEASED") } }
            sink.onAudioStopped(media)
            await { logs.any { it.contains("mediaRecovery") && it.contains("ended=true") } }
            val recovery = logs.last { it.contains("mediaRecovery") }
            assertTrue(recovery.contains("callPhase=AFTER_RELEASE"))
            assertTrue(recovery.contains("callDownlinks=0 callUplinks=0"))
            assertTrue(recovery.contains("focusEnabled=false"))
            assertFalse(logs.any { it.contains("renderer failed") })
        } finally { sink.close() }
    }

    @Test fun aSingleVoiceSampleCanStartTheWirelessTrackWithoutTheFourKilobyteFloor() {
        val logs = CopyOnWriteArrayList<String>()
        val sink = AndroidMediaSink(wirelessAudio = true, audioFocusEnabled = false, onAudioDiagnostic = logs::add)
        try {
            val id = AudioStreamId(100, "speechrecognition")
            val format = AudioFormat(AudioCodecKind.LPCM, 16_000, 1, 100, id.audioType)
            sink.onAudioStarted(id, format, 0)
            await { logs.any { it.startsWith("Audio: ready") } }
            val renderers = ReflectionHelpers.getField<Map<AudioStreamId, Any>>(sink, "audioRenderers")
            val renderer = renderers.getValue(id)
            val track = ReflectionHelpers.getField<AudioTrack>(renderer, "track")
            assertEquals(2, ReflectionHelpers.getField<Int>(renderer, "startThresholdBytes"))
            assertTrue(logs.any { it.contains("jitterTargetMs=80") && it.contains("wireless=true") })
            sink.onAudioRtp(id, format, ByteArray(14).also { it[12] = 4 }, 0)
            await { track.playState == AudioTrack.PLAYSTATE_PLAYING }
        } finally { sink.close() }
    }

    @Test fun wirelessMainMusicKeepsItsIndependentBufferedPath() {
        val logs = CopyOnWriteArrayList<String>()
        val sink = AndroidMediaSink(wirelessAudio = true, audioFocusEnabled = false, onAudioDiagnostic = logs::add)
        try {
            val id = AudioStreamId(102, "media")
            sink.onAudioStarted(id, AudioFormat(AudioCodecKind.LPCM, 48_000, 2, 102, "media"), 0)
            await { logs.any { it.startsWith("Audio: ready") } }
            assertTrue(logs.any { it.contains("jitterTargetMs=0") && it.contains("startMs=300") })
        } finally { sink.close() }
    }
}
