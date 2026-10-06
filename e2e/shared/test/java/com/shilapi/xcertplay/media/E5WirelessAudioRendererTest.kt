package com.shilapi.xcertplay.media

import android.media.AudioTrack
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.AudioStreamId
import org.junit.Assert.*
import org.mockito.Mockito.*
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

    @Test fun mediaWorkerPreservesPcmAcrossZeroWriteAndRestartsPlayingTrackWithoutFlush() {
        val logs = CopyOnWriteArrayList<String>()
        val sink = AndroidMediaSink(wirelessAudio = true, audioFocusEnabled = false, onAudioDiagnostic = logs::add)
        val id = AudioStreamId(102, "media")
        val format = AudioFormat(AudioCodecKind.LPCM, 48_000, 2, 102, "media")
        var original: AudioTrack? = null
        try {
            sink.onAudioStarted(id, format, 0)
            await { logs.any { it.startsWith("Audio: ready") } }
            val renderer = ReflectionHelpers.getField<Map<AudioStreamId, Any>>(sink, "audioRenderers").getValue(id)
            original = ReflectionHelpers.getField(renderer, "track")
            val track = mock(AudioTrack::class.java)
            `when`(track.playState).thenReturn(AudioTrack.PLAYSTATE_PLAYING)
            val writes = CopyOnWriteArrayList<Pair<Int, ByteArray>>()
            `when`(track.write(any(ByteArray::class.java), anyInt(), anyInt(), anyInt())).thenAnswer { call ->
                val offset = call.getArgument<Int>(1); val length = call.getArgument<Int>(2)
                assertEquals(AudioTrack.WRITE_NON_BLOCKING, call.getArgument<Int>(3))
                writes.add(offset to call.getArgument<ByteArray>(0).copyOfRange(offset, offset + length))
                if (writes.size == 1) 0 else length
            }
            ReflectionHelpers.setField(renderer, "track", track)
            ReflectionHelpers.setField(renderer, "playbackStarted", true)
            ReflectionHelpers.setField(renderer, "fadeApplied", true)
            ReflectionHelpers.callInstanceMethod<Unit>(renderer, "resumeAfterCommunication")
            sink.onAudioRtp(id, format, ByteArray(16).also { it[12] = 1; it[13] = 2; it[14] = 3; it[15] = 4 }, 0)
            await { writes.size >= 2 }
            assertEquals(0, writes[0].first)
            assertEquals(0, writes[1].first)
            assertArrayEquals(writes[0].second, writes[1].second)
            assertArrayEquals(byteArrayOf(2, 1, 4, 3), writes[1].second)
            verify(track).pause(); verify(track).play()
            verify(track, never()).flush()
            assertFalse(logs.any { "renderer failed" in it })
        } finally { sink.close(); original?.release() }
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
