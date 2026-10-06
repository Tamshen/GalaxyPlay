package com.shilapi.xcertplay.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import com.shilapi.xcertplay.airplay.VideoCodec
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 运行真实视频 worker 与队列；仅以 codec 替身控制系统创建／输入耗时，不模拟解码成功。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class VideoDecoderRecoveryTest {
    @Test fun setupBeyondFrameBudgetDropsOldPicturesAndAcceptsANewKeyframeWithoutAnotherRebuild() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        Fixture().use { fixture ->
            doAnswer { entered.countDown(); check(release.await(2, TimeUnit.SECONDS)); null }
                .`when`(fixture.codec).start()
            try {
                fixture.configure()
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                fixture.submit(key)
                fixture.submit(predicted)
                assertFalse(fixture.ready.await(300, TimeUnit.MILLISECONDS))
                release.countDown()
                assertTrue(fixture.ready.await(2, TimeUnit.SECONDS))
                assertEquals(0, fixture.gate.failures)
                assertEquals(0, fixture.queue.queuedFrames)
                fixture.submit(predicted)
                fixture.barrier()
                verify(fixture.codec, never()).queueInputBuffer(anyInt(), anyInt(), anyInt(), anyLong(), anyInt())
                fixture.submit(key)
                fixture.barrier()
                verify(fixture.codec).queueInputBuffer(eq(0), eq(0), eq(key.size), anyLong(), eq(0))
                assertEquals(1, fixture.creations.get())
                assertEquals(0, fixture.gate.failures)
            } finally { release.countDown() }
        }
    }

    @Test fun aLateInputSlotIsRetainedAndReusedWithoutStoppingTheCodec() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        Fixture().use { fixture ->
            fixture.configure()
            assertTrue(fixture.ready.await(2, TimeUnit.SECONDS))
            doAnswer { entered.countDown(); check(release.await(2, TimeUnit.SECONDS)); 0 }
                .`when`(fixture.codec).dequeueInputBuffer(anyLong())
            try {
                fixture.submit(key)
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                assertFalse(fixture.inputQueued.await(300, TimeUnit.MILLISECONDS))
                release.countDown()
                fixture.barrier()
                verify(fixture.codec, never()).queueInputBuffer(anyInt(), anyInt(), anyInt(), anyLong(), anyInt())
                fixture.submit(key)
                fixture.barrier()
                assertTrue(fixture.inputQueued.await(2, TimeUnit.SECONDS))
                verify(fixture.codec, times(1)).dequeueInputBuffer(anyLong())
                verify(fixture.codec, never()).stop()
                assertEquals(0, fixture.gate.failures)
            } finally { release.countDown() }
        }
    }

    @Test fun duplicateConfigurationKeepsTheCodecAndOnlyFreshCurrentPresentationClearsFailure() {
        Fixture().use { fixture ->
            fixture.configure()
            assertTrue(fixture.ready.await(2, TimeUnit.SECONDS))
            fixture.configure()
            fixture.barrier()
            assertEquals(1, fixture.creations.get())
            fixture.field("failureReported").setBoolean(fixture.worker, true)
            val callback = fixture.presentation.get()
            val now = System.nanoTime()
            callback.onFrameRendered(mock(MediaCodec::class.java), now / 1000, now)
            callback.onFrameRendered(fixture.codec, (now - 300_000_000L) / 1000, now)
            assertEquals(0, fixture.recovered.get())
            callback.onFrameRendered(fixture.codec, System.nanoTime() / 1000, System.nanoTime())
            assertEquals(1, fixture.recovered.get())
        }
    }

    @Test fun manualRetryIsDeduplicatedWhileQueuedAndShutdownCannotBeRevived() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fixture = Fixture()
        try {
            doAnswer { entered.countDown(); check(release.await(2, TimeUnit.SECONDS)); null }
                .`when`(fixture.codec).start()
            fixture.configure()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(fixture.retry())
            repeat(10) { assertFalse(fixture.retry()) }
            fixture.close()
            assertFalse(fixture.retry())
            verify(fixture.codec, atLeastOnce()).release()
        } finally { release.countDown(); fixture.close() }
    }

    private class Fixture : Closeable {
        val codec = mock(MediaCodec::class.java)
        val ready = CountDownLatch(1)
        val inputQueued = CountDownLatch(1)
        val recovered = AtomicInteger()
        val creations = AtomicInteger()
        val presentation = AtomicReference<MediaCodec.OnFrameRenderedListener>()
        private val closed = CountDownLatch(1)
        private val reports = ConcurrentLinkedQueue<String>()
        private val type = Class.forName("com.shilapi.xcertplay.media.VideoDecoder")
        val worker: Any
        val gate: VideoRecoveryGate get() = field("recoveryGate").get(worker) as VideoRecoveryGate
        val queue: VideoDecodeQueue get() = field("queue").get(worker) as VideoDecodeQueue
        init {
            val surface = mock(Surface::class.java)
            `when`(surface.isValid).thenReturn(true)
            val info = mock(MediaCodecInfo::class.java)
            `when`(info.isHardwareAccelerated).thenReturn(true)
            `when`(codec.codecInfo).thenReturn(info)
            `when`(codec.name).thenReturn("test.avc")
            `when`(codec.dequeueOutputBuffer(any(MediaCodec.BufferInfo::class.java), eq(0L)))
                .thenReturn(MediaCodec.INFO_TRY_AGAIN_LATER)
            `when`(codec.dequeueInputBuffer(anyLong())).thenReturn(0)
            `when`(codec.getInputBuffer(0)).thenReturn(ByteBuffer.allocate(64))
            doAnswer { presentation.set(it.getArgument(0)); null }.`when`(codec)
                .setOnFrameRenderedListener(any(MediaCodec.OnFrameRenderedListener::class.java), any())
            doAnswer { inputQueued.countDown(); null }.`when`(codec)
                .queueInputBuffer(anyInt(), anyInt(), anyInt(), anyLong(), anyInt())
            val report: (String) -> Unit = { reports.add(it); if (it.startsWith("decoder=test.avc")) ready.countDown() }
            val failure: (Any, VideoCodec, String) -> Unit = { _, _, _ -> }
            val recoveredCallback: (Any) -> Unit = { recovered.incrementAndGet() }
            val closedCallback: (Any) -> Unit = { closed.countDown() }
            val size: (Int, Int) -> Unit = { _, _ -> }
            val create: (String) -> MediaCodec = { creations.incrementAndGet(); codec }
            worker = type.declaredConstructors.single { it.parameterCount == 12 }.apply { isAccessible = true }
                .newInstance(110, surface, 1280, 720, false, {}, report, failure,
                    recoveredCallback, closedCallback, size, create)
            @Suppress("UNCHECKED_CAST")
            val candidates = field("decoderCandidateCache").get(worker) as MutableMap<String, List<VideoDecoderCandidate>>
            candidates[MediaFormat.MIMETYPE_VIDEO_AVC] = listOf(VideoDecoderCandidate("test.avc", true, false, false))
        }
        fun field(name: String) = type.getDeclaredField(name).apply { isAccessible = true }
        fun configure() = type.getDeclaredMethod("configure", VideoCodec::class.java, ByteArray::class.java)
            .apply { isAccessible = true }.invoke(worker, VideoCodec.H264, byteArrayOf(1))
        fun submit(bytes: ByteArray) = type.getDeclaredMethod("submit", ByteArray::class.java)
            .apply { isAccessible = true }.invoke(worker, bytes)
        fun retry() = type.getDeclaredMethod("retry").apply { isAccessible = true }.invoke(worker) as Boolean
        fun barrier() {
            val applied = CountDownLatch(1)
            val surface = field("desiredSurface").get(worker) as Surface
            queue.offer(VideoJob.SurfaceChanged(surface) { applied.countDown() })
            assertTrue(applied.await(2, TimeUnit.SECONDS))
        }
        override fun close() {
            (worker as Closeable).close()
            assertTrue(closed.await(2, TimeUnit.SECONDS))
        }
    }
    companion object {
        private val key = byteArrayOf(0, 0, 0, 1, 0x65, 1)
        private val predicted = byteArrayOf(0, 0, 0, 1, 0x41, 1)
    }
}
