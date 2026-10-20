package com.shilapi.xcertplay.media

import android.media.AudioTrack
import android.media.PlaybackParams
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.Closeable

/** 经正式建轨、PCM 写入与欠缓冲恢复入口验证：缓冲变化不能改变音乐采样率或样本。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30], manifest = Config.NONE)
class AudioPitchStabilityTest {
    private class Playback(val rate: Int, wireless: Boolean) : Closeable {
        private val type = Class.forName("com.shilapi.xcertplay.media.AudioRenderer")
        val reports = mutableListOf<String>()
        private val renderer = type.declaredConstructors.single { it.parameterCount == 19 }
            .apply { isAccessible = true }
            .newInstance(AudioFormat(AudioCodecKind.LPCM, rate, 2, 96, "media"),
                false, false, 0, 0, 0, AudioFocusCoordinator(null, false), null, null, 0, 300,
                { message: String -> reports.add(message) }, wireless,
                CallAudioTimeline(report = { _: String -> }), { "test" }, { _: Any -> }, { _: Any -> }, 0, false)
        private val original: AudioTrack
        val track = mock(AudioTrack::class.java)
        val output = ByteArrayOutputStream()
        var head = 0
        var underruns = 0
        private var state = AudioTrack.PLAYSTATE_STOPPED
        private var actualRate = rate
        val threshold: Int get() = get("startThresholdBytes") as Int
        val playing: Boolean get() = get("playbackStarted") as Boolean

        init {
            call("createTrack")
            original = get("track") as AudioTrack
            assertEquals(rate, original.sampleRate)
            `when`(track.playbackHeadPosition).thenAnswer { head }
            `when`(track.underrunCount).thenAnswer { underruns }
            `when`(track.playState).thenAnswer { state }
            `when`(track.sampleRate).thenAnswer { actualRate }
            `when`(track.playbackRate).thenAnswer { actualRate }
            doAnswer { state = AudioTrack.PLAYSTATE_PLAYING; null }.`when`(track).play()
            doAnswer { state = AudioTrack.PLAYSTATE_PAUSED; null }.`when`(track).pause()
            `when`(track.setPlaybackRate(anyInt())).thenAnswer {
                actualRate = it.getArgument(0)
                AudioTrack.SUCCESS
            }
            `when`(track.write(any(ByteArray::class.java), anyInt(), anyInt(), anyInt())).thenAnswer {
                val bytes = it.getArgument<ByteArray>(0)
                val offset = it.getArgument<Int>(1)
                val size = it.getArgument<Int>(2)
                output.write(bytes, offset, size)
                size
            }
            set("track", track)
            // 淡入另有覆盖；这里验证已有音乐样本不会被调速或重采样。
            set("fadeApplied", true)
        }

        private fun get(name: String): Any? = type.getDeclaredField(name).apply { isAccessible = true }.get(renderer)
        fun set(name: String, value: Any?) = type.getDeclaredField(name).apply { isAccessible = true }.set(renderer, value)
        fun call(name: String) = type.getDeclaredMethod(name).apply { isAccessible = true }.invoke(renderer)
        fun observeGap() = (get("musicJitter") as MusicJitterRecovery).observe(1_000)
        fun logStats() = type.getDeclaredMethod("logStatsIfDue", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(renderer, true)
        fun write(bytes: ByteArray) = type.getDeclaredMethod("writePcm", ByteArray::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(renderer, bytes, 0, bytes.size)

        fun assertOriginalRate() {
            verify(track, never()).setPlaybackRate(anyInt())
            verify(track, never()).setPlaybackParams(any(PlaybackParams::class.java))
            assertEquals(rate, actualRate)
        }

        override fun close() { call("release"); original.release() }
    }

    @Test fun lowMusicBufferPreservesPitchAndPcmForBothSampleRatesAndTransports() {
        for (rate in listOf(44_100, 48_000)) for (wireless in listOf(false, true)) {
            Playback(rate, wireless).use {
                it.set("playbackStarted", true)
                val samples = ByteArray(it.threshold / 2) { index -> (index * 13).toByte() }
                it.write(samples)
                it.assertOriginalRate()
                assertArrayEquals(samples, it.output.toByteArray())
            }
        }
    }

    @Test fun highMusicBufferCannotSpeedUpOrRaisePitch() {
        Playback(48_000, true).use {
            it.set("playbackStarted", true)
            it.write(ByteArray(it.threshold * 3 / 2))
            it.assertOriginalRate()
        }
    }

    @Test fun underrunRefillsAndResumesAtTheOriginalRate() {
        Playback(48_000, true).use {
            it.write(ByteArray(it.threshold))
            assertTrue(it.playing)
            it.head = it.threshold / 4
            it.underruns = 1
            it.call("maintainPlaybackBuffer")
            assertFalse(it.playing)
            it.write(ByteArray(it.threshold))
            assertTrue(it.playing)
            // 恢复后再次低于启动水位，不能通过降调维持缓冲。
            it.head += it.threshold / 8
            it.write(ByteArray(1_920))
            it.assertOriginalRate()
            verify(it.track, times(2)).play()
            verify(it.track).pause()
            verify(it.track, never()).flush()
        }
    }

    @Test fun nextSongStartsAtItsOwnSampleRateAfterLowBufferPlayback() {
        for (rate in listOf(48_000, 44_100, 48_000)) {
            Playback(rate, false).use {
                it.set("playbackStarted", true)
                it.write(ByteArray(it.threshold / 2))
                it.assertOriginalRate()
            }
        }
    }

    @Test fun jitterRecoveryChangesTheWaterlineWithoutChangingPitch() {
        Playback(48_000, true).use {
            val before = it.threshold
            it.set("playbackStarted", true)
            it.observeGap()
            it.call("maintainPlaybackBuffer")
            assertTrue(it.threshold > before)
            it.write(ByteArray(1_920))
            it.assertOriginalRate()
        }
    }

    @Test fun diagnosticsRecordSourceAndActualPlaybackRates() {
        Playback(44_100, true).use {
            it.set("playbackStarted", true)
            it.write(ByteArray(it.threshold / 2))
            it.logStats()
            val stats = it.reports.single { line -> line.startsWith("audio stats") }
            assertTrue(stats, "sourceRate=44100 sampleRate=44100" in stats)
            assertTrue(stats, "playbackRate=44100 ended=true" in stats)
        }
    }
}
