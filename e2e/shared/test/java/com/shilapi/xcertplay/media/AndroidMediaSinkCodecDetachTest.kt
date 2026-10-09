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

/** Tests existing asynchronous detach; never adds parking or another video output. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class AndroidMediaSinkCodecDetachTest {
    @Test fun closedRetiredDecoderMustReleaseBeforeDetachAcknowledgement() {
        val entered = CountDownLatch(1); val resume = CountDownLatch(1); val detached = CountDownLatch(1)
        Fixture().use { f ->
            val sink = AndroidMediaSink(); val workerMap = field(sink, "videoDecoders") as MutableMap<Int, Any>
            workerMap[110] = f.worker
            doAnswer {
                entered.countDown()
                while (resume.count != 0L) try { resume.await() } catch (_: InterruptedException) { }
                null
            }.`when`(f.codec).start()
            try {
                f.configure(); assertTrue(entered.await(2, TimeUnit.SECONDS))
                sink.close()
                sink.detachSurface(f.surface) { assertEquals(0L, f.released.count); detached.countDown() }
                assertEquals(1L, detached.count) // callback was not UI-blocking and did not release early
                resume.countDown()
                assertTrue(detached.await(2, TimeUnit.SECONDS))
                verify(f.codec, times(1)).release()
            } finally { resume.countDown(); sink.close() }
        }
    }

    @Test fun oldAndNewSinksAcknowledgeIndependentlyAndLateCallbacksDoNotReviveCodecs() {
        val surface = mock(Surface::class.java)
        Fixture(surface).use { old -> Fixture(surface).use { next ->
            val a = AndroidMediaSink(); val b = AndroidMediaSink()
            @Suppress("UNCHECKED_CAST")
            (field(a, "videoDecoders") as MutableMap<Int, Any>)[110] = old.worker
            @Suppress("UNCHECKED_CAST")
            (field(b, "videoDecoders") as MutableMap<Int, Any>)[110] = next.worker
            old.configure(); next.configure()
            assertTrue(old.ready.await(2, TimeUnit.SECONDS)); assertTrue(next.ready.await(2, TimeUnit.SECONDS))
            val callbacks = old.presentation.get() to next.presentation.get()
            val detached = CountDownLatch(2)
            a.close(); a.detachSurface(surface) { detached.countDown() }
            b.detachSurface(surface) { detached.countDown() }
            assertTrue(detached.await(2, TimeUnit.SECONDS))
            callbacks.first.onFrameRendered(old.codec, System.nanoTime() / 1000, System.nanoTime())
            callbacks.second.onFrameRendered(next.codec, System.nanoTime() / 1000, System.nanoTime())
            assertEquals(0, old.recovered.get()); assertEquals(0, next.recovered.get())
            a.close(); b.close()
        } }
    }

    private fun field(sink: AndroidMediaSink, name: String) = sink.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(sink)
    private class Fixture(val surface: Surface = mock(Surface::class.java)) : Closeable {
        val codec = mock(MediaCodec::class.java)
        val ready = CountDownLatch(1)
        val inputQueued = CountDownLatch(1)
        val recovered = AtomicInteger()
        val creations = AtomicInteger()
        val released = CountDownLatch(1)
        val presentation = AtomicReference<MediaCodec.OnFrameRenderedListener>()
        private val closed = CountDownLatch(1)
        private val reports = ConcurrentLinkedQueue<String>()
        private val type = Class.forName("com.shilapi.xcertplay.media.VideoDecoder")
        val worker: Any
        val gate: VideoRecoveryGate get() = field("recoveryGate").get(worker) as VideoRecoveryGate
        val queue: VideoDecodeQueue get() = field("queue").get(worker) as VideoDecodeQueue
        init {
            `when`(surface.isValid).thenReturn(true)
            doAnswer { released.countDown(); null }.`when`(codec).release()
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
            worker = type.declaredConstructors.single { it.parameterCount == 14 }.apply { isAccessible = true }
                .newInstance(110, surface, 1280, 720, false, 30, {}, report, failure,
                    recoveredCallback, closedCallback, size, create, null)
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
}
