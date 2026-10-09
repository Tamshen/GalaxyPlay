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

/** Exercises operating-rate rejection and presentation using the production worker. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class VideoOperatingRateTest {
    @Test fun usesNegotiatedFpsOnlyOnTheMainStream() {
        for (fps in listOf(30, 60)) Fixture(fps).use {
            it.configure(); assertTrue(it.ready.await(2, TimeUnit.SECONDS)); it.barrier()
            assertEquals(fps, it.formats.single().getInteger(MediaFormat.KEY_OPERATING_RATE))
        }
        Fixture(60, 111).use {
            it.configure(); assertTrue(it.ready.await(2, TimeUnit.SECONDS)); it.barrier()
            assertFalse(it.formats.single().containsKey(MediaFormat.KEY_OPERATING_RATE))
        }
    }

    @Test fun configureRejectionKeepsTunedFallbackAndNeverRepeatsHint() {
        Fixture().use { f ->
            doAnswer {
                val format = it.getArgument<MediaFormat>(0); f.formats.add(format)
                if (format.containsKey(MediaFormat.KEY_OPERATING_RATE)) throw IllegalArgumentException("rate refused")
                null
            }.`when`(f.codec).configure(any(MediaFormat::class.java), any(Surface::class.java), isNull(), eq(0))
            f.configure(); assertTrue(f.ready.await(2, TimeUnit.SECONDS)); f.barrier()
            assertEquals(2, f.creations.get())
            assertTrue(f.formats[1].containsKey(MediaFormat.KEY_PRIORITY))
            assertFalse(f.formats[1].containsKey(MediaFormat.KEY_OPERATING_RATE))
            assertEquals(0, f.field("operatingRate").getInt(f.worker))
            assertTrue(f.retry()); f.barrier()
            assertEquals(1, f.formats.count { it.containsKey(MediaFormat.KEY_OPERATING_RATE) })
            assertEquals(0, f.gate.failures)
        }
    }

    @Test fun startRejectionAlsoReleasesHintedCodecAndFallsBack() {
        Fixture().use { f ->
            val starts = AtomicInteger()
            doAnswer { if (starts.incrementAndGet() == 1) throw IllegalStateException("start refused"); null }.`when`(f.codec).start()
            f.configure(); assertTrue(f.ready.await(2, TimeUnit.SECONDS)); f.barrier()
            assertEquals(2, starts.get()); assertEquals(0, f.field("configuredRate").getInt(f.worker))
            verify(f.codec, times(1)).release()
        }
    }

    @Test fun onlyARealFreshPresentationProtectsTheAcceptedHintFromEarlyCodecFailure() {
        for (presented in listOf(false, true)) Fixture().use { f ->
            f.configure(); assertTrue(f.ready.await(2, TimeUnit.SECONDS)); f.barrier()
            val callback = f.presentation.get(); val now = System.nanoTime()
            callback.onFrameRendered(f.codec, (now - 300_000_000L) / 1000, now)
            assertEquals(0, f.recovered.get())
            if (presented) callback.onFrameRendered(f.codec, System.nanoTime() / 1000, System.nanoTime())
            assertEquals(30, f.field("configuredRate").getInt(f.worker))
            val codecFailure = MediaCodec.CodecException::class.java
                .getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java)
                .apply { isAccessible = true }.newInstance(1100, 0, "test codec failure")
            // worker 已启动后只发布故障，不并发重新设置正在调用的 Mockito 桩。
            f.outputFailure.set(codecFailure)
            assertTrue(f.outputFailed.await(2, TimeUnit.SECONDS)); f.barrier()
            assertEquals(f.reports.toString(), if (presented) 30 else 0, f.field("operatingRate").getInt(f.worker))
            assertTrue(f.retry()); f.barrier()
            assertEquals(if (presented) 2 else 1, f.formats.count { it.containsKey(MediaFormat.KEY_OPERATING_RATE) })
        }
    }
    private class Fixture(val fps: Int = 30, val stream: Int = 110) : Closeable {
        val codec = mock(MediaCodec::class.java)
        val ready = CountDownLatch(1)
        val inputQueued = CountDownLatch(1)
        val recovered = AtomicInteger()
        val creations = AtomicInteger()
        val formats = java.util.concurrent.CopyOnWriteArrayList<MediaFormat>()
        val presentation = AtomicReference<MediaCodec.OnFrameRenderedListener>()
        val outputFailure = AtomicReference<RuntimeException?>()
        val outputFailed = CountDownLatch(1)
        private val closed = CountDownLatch(1)
        val reports = ConcurrentLinkedQueue<String>()
        private val type = Class.forName("com.shilapi.xcertplay.media.VideoDecoder")
        val worker: Any
        val gate: VideoRecoveryGate get() = field("recoveryGate").get(worker) as VideoRecoveryGate
        val queue: VideoDecodeQueue get() = field("queue").get(worker) as VideoDecodeQueue
        init {
            doAnswer { formats.add(it.getArgument(0)); null }.`when`(codec).configure(any(MediaFormat::class.java), any(Surface::class.java), isNull(), eq(0))
            val surface = mock(Surface::class.java)
            `when`(surface.isValid).thenReturn(true)
            val info = mock(MediaCodecInfo::class.java)
            `when`(info.isHardwareAccelerated).thenReturn(true)
            `when`(codec.codecInfo).thenReturn(info)
            `when`(codec.name).thenReturn("test.avc")
            doAnswer {
                outputFailure.getAndSet(null)?.let { failure ->
                    outputFailed.countDown()
                    throw failure
                }
                MediaCodec.INFO_TRY_AGAIN_LATER
            }.`when`(codec).dequeueOutputBuffer(any(MediaCodec.BufferInfo::class.java), eq(0L))
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
                .newInstance(stream, surface, 1280, 720, false, fps, {}, report, failure,
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
