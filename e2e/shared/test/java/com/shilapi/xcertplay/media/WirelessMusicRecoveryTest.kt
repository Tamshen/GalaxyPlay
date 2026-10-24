package com.shilapi.xcertplay.media

import android.media.AudioTrack
import android.media.MediaCodec
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 直接运行真实音乐 worker 方法；替身仅控制解码器背压与原车 AudioTrack 容量。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30], manifest = Config.NONE)
class WirelessMusicRecoveryTest {
    private val type = Class.forName("com.shilapi.xcertplay.media.AudioRenderer")
    private fun renderer(wireless: Boolean = true) = type.declaredConstructors.single { it.parameterCount == 19 }
        .apply { isAccessible = true }.newInstance(
            AudioFormat(AudioCodecKind.AAC_LC, 48_000, 2, 96, "media"), false, false, 0, 0, 0,
            AudioFocusCoordinator(null, false), null, null, 0, 300, { _: String -> }, wireless,
            CallAudioTimeline(report = { _: String -> }), { "test" }, { _: Any -> }, { _: Any -> }, 0, false)
    private fun set(worker: Any, name: String, value: Any) = type.getDeclaredField(name)
        .apply { isAccessible = true }.set(worker, value)
    private fun get(worker: Any, name: String) = type.getDeclaredField(name).apply { isAccessible = true }.get(worker)
    private fun maintain(worker: Any) = type.getDeclaredMethod("maintainPlaybackBuffer")
        .apply { isAccessible = true }.invoke(worker)

    @Test fun releasingPendingOutputLetsTheSameCompressedPacketEnterWithoutBeingDropped() {
        val worker = renderer()
        val codec = mock(MediaCodec::class.java)
        var released = false
        var outputReturned = false
        `when`(codec.dequeueInputBuffer(anyLong())).thenAnswer { if (released) 0 else -1 }
        `when`(codec.dequeueOutputBuffer(any(MediaCodec.BufferInfo::class.java), eq(0L))).thenAnswer { call ->
            if (outputReturned) MediaCodec.INFO_TRY_AGAIN_LATER else {
                outputReturned = true
                call.getArgument<MediaCodec.BufferInfo>(0).set(0, 0, 0, 0)
                2
            }
        }
        doAnswer { released = true; null }.`when`(codec).releaseOutputBuffer(2, false)
        val input = ByteBuffer.allocate(32)
        `when`(codec.getInputBuffer(0)).thenReturn(input)
        set(worker, "codec", codec); set(worker, "mappedChannel", AudioChannel.MEDIA)
        val bytes = byteArrayOf(10, 20, 30, 40)
        type.getDeclaredMethod("feedCodec", ByteArray::class.java, Long::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(worker, bytes, 1234L)
        verify(codec).queueInputBuffer(0, 0, 4, 1234L, 0)
        assertArrayEquals(bytes, input.array().copyOf(4))
        assertEquals(0, get(worker, "inputDropped"))
        assertEquals(1, get(worker, "inputQueued"))
        assertEquals(1L, (get(worker, "codecInputWait") as AudioCodecInputWait).retries)
    }

    @Test fun underrunWithQueuedBurstRetainsPcmAndRefillsToTheConfiguredWaterlineWithoutFlushingOrBlocking() {
        val worker = renderer()
        val track = mock(AudioTrack::class.java)
        var playing = true
        var queued = 8000
        val written = mutableListOf<Byte>()
        `when`(track.underrunCount).thenReturn(1)
        `when`(track.playbackHeadPosition).thenReturn(0)
        doAnswer { playing = false; null }.`when`(track).pause()
        doAnswer { playing = true; null }.`when`(track).play()
        `when`(track.write(any(ByteArray::class.java), anyInt(), anyInt(), anyInt())).thenAnswer { call ->
            val bytes = call.getArgument<ByteArray>(0)
            val offset = call.getArgument<Int>(1); val size = call.getArgument<Int>(2)
            assertEquals(AudioTrack.WRITE_NON_BLOCKING, call.getArgument<Int>(3))
            assertTrue("预缓冲不能等待超过原车实际容量", playing || queued + size <= 96_000)
            queued += size
            written.addAll(bytes.copyOfRange(offset, offset + size).toList())
            size
        }
        set(worker, "track", track); set(worker, "mappedChannel", AudioChannel.MEDIA)
        set(worker, "playbackStarted", true); set(worker, "startThresholdBytes", 57_600)
        set(worker, "bytesPerSecond", 192_000)
        set(worker, "fadeApplied", true)
        (get(worker, "bufferProgress") as AudioBufferProgress).written(8000)
        (get(worker, "musicJitter") as MusicJitterRecovery).observe(400)
        @Suppress("UNCHECKED_CAST") val queue = get(worker, "queue") as LinkedBlockingQueue<Any>
        val packet = Class.forName("com.shilapi.xcertplay.media.AudioRenderer\$AudioPacket")
            .declaredConstructors.single().apply { isAccessible = true }.newInstance(ByteArray(16), 1, 0L)
        queue.add(packet)
        maintain(worker)
        assertFalse(playing)
        assertEquals(8000, get(worker, "prebufferBytes"))
        // DiPlay 的音乐水位固定为用户所选档位（300 毫秒 = 57 600 字节），不会因实测抖动被抬高。
        assertEquals(57_600, get(worker, "startThresholdBytes"))
        assertEquals(1, get(worker, "rebufferCount"))
        val remaining = ByteArray(57_600 - 8000) { 42 }
        type.getDeclaredMethod("writePcm", ByteArray::class.java, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(worker, remaining, 0, remaining.size)
        assertTrue(playing)
        assertEquals(57_600, queued)
        assertArrayEquals(remaining, written.toByteArray())
        maintain(worker)
        assertEquals(1, get(worker, "rebufferCount"))
        verify(track, never()).flush()
        assertEquals(1, queue.size)
    }
}
