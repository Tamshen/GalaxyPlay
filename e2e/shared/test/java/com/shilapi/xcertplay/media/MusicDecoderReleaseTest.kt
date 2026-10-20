package com.shilapi.xcertplay.media

import android.media.AudioTrack
import android.media.MediaCodec
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 通过正式 drainCodec 入口确认：播放阻塞时已交还 codec，PCM 未被提前覆盖。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class MusicDecoderReleaseTest {
    @Test fun codecBufferIsReleasedBeforeWaitingForPcmCapacityAndPartialWritesKeepOrder() {
        val type = Class.forName("com.shilapi.xcertplay.media.AudioRenderer")
        val renderer = type.declaredConstructors.single { it.parameterCount == 19 }.apply { isAccessible = true }
            .newInstance(AudioFormat(AudioCodecKind.AAC_LC, 48_000, 2, 96, "media"),
                false, false, 0, 0, 0, AudioFocusCoordinator(null, false), null, null, 0, 300,
                { _: String -> }, true, CallAudioTimeline(report = { _: String -> }),
                { "test" }, { _: Any -> }, { _: Any -> }, 0, true)
        fun field(name: String) = type.getDeclaredField(name).apply { isAccessible = true }
        fun call(name: String) = type.getDeclaredMethod(name).apply { isAccessible = true }.invoke(renderer)
        val track = mock(AudioTrack::class.java)
        val codec = mock(MediaCodec::class.java)
        val source = ByteArray(32_768) { (it % 251).toByte() }
        val output = java.io.ByteArrayOutputStream()
        val writing = CountDownLatch(1)
        val allowWrite = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val released = CountDownLatch(1)
        val drained = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val calls = AtomicInteger()
        field("track").set(renderer, track)
        field("mappedChannel").set(renderer, AudioChannel.MEDIA)
        field("playbackStarted").setBoolean(renderer, true)
        field("fadeApplied").setBoolean(renderer, true)
        `when`(track.playState).thenReturn(AudioTrack.PLAYSTATE_PLAYING)
        `when`(track.write(any(ByteArray::class.java), anyInt(), anyInt(), anyInt())).thenAnswer {
            writing.countDown()
            check(allowWrite.await(2, TimeUnit.SECONDS))
            assertEquals(AudioTrack.WRITE_NON_BLOCKING, it.getArgument<Int>(3))
            if (calls.getAndIncrement() == 0) 0 else {
                val length = minOf(512, it.getArgument<Int>(2))
                output.write(it.getArgument<ByteArray>(0), it.getArgument(1), length)
                if (output.size() == source.size) completed.countDown()
                length
            }
        }
        val dequeues = AtomicInteger()
        `when`(codec.dequeueOutputBuffer(any(MediaCodec.BufferInfo::class.java), eq(0L))).thenAnswer {
            if (dequeues.getAndIncrement() == 0) {
                it.getArgument<MediaCodec.BufferInfo>(0).set(0, source.size, 0, 0)
                0
            } else MediaCodec.INFO_TRY_AGAIN_LATER
        }
        `when`(codec.getOutputBuffer(0)).thenReturn(ByteBuffer.wrap(source))
        doAnswer { released.countDown(); null }.`when`(codec).releaseOutputBuffer(0, false)
        call("startPcmWriter")
        val producer = Thread {
            try {
                type.getDeclaredMethod("drainCodec", MediaCodec::class.java).apply { isAccessible = true }
                    .invoke(renderer, codec)
            } catch (error: Throwable) { failure.set(error) }
            finally { drained.countDown() }
        }.apply { start() }
        try {
            assertTrue(writing.await(2, TimeUnit.SECONDS))
            assertEquals(0L, released.count)
            assertFalse(drained.await(40, TimeUnit.MILLISECONDS))
            allowWrite.countDown()
            assertTrue(drained.await(2, TimeUnit.SECONDS))
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            assertNull(failure.get())
            assertArrayEquals(source, output.toByteArray())
            verify(codec, times(1)).releaseOutputBuffer(0, false)
        } finally {
            allowWrite.countDown()
            call("close")
            (field("pcmWriter").get(renderer) as MusicPcmWriter).stopAndJoin()
            producer.join(2000)
            call("release")
        }
    }
}
