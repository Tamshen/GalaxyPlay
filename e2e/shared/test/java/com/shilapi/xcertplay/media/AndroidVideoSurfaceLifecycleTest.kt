package com.shilapi.xcertplay.media

import android.graphics.SurfaceTexture
import android.view.Surface
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 验证 worker 完成与关闭竞态；真实 codec/SurfaceTexture 绑定仍需实车验收。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class AndroidVideoSurfaceLifecycleTest {
    @Test fun detachIsAcknowledgedByTheWorker() {
        val sink = AndroidMediaSink()
        val texture = SurfaceTexture(0)
        val surface = Surface(texture)
        val completed = CountDownLatch(1)
        var threadName = ""
        try {
            sink.setSurface(110, surface)
            sink.onVideoFrame(110, byteArrayOf(1))
            sink.detachSurface(surface) { threadName = Thread.currentThread().name; completed.countDown() }
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            assertEquals("carplay-video", threadName)
        } finally { sink.close(); surface.release(); texture.release() }
    }

    @Test fun stoppingBeforeDetachStillCompletesExactlyOnce() {
        val sink = AndroidMediaSink()
        val texture = SurfaceTexture(0)
        val surface = Surface(texture)
        val completed = CountDownLatch(1)
        val count = AtomicInteger()
        try {
            sink.setSurface(110, surface)
            sink.onVideoFrame(110, byteArrayOf(1))
            sink.close()
            sink.detachSurface(surface) { count.incrementAndGet(); completed.countDown() }
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            assertEquals(1, count.get())
            val field = sink.javaClass.getDeclaredField("retiringVideoDecoders").apply { isAccessible = true }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (System.nanoTime() < deadline && (field.get(sink) as Set<*>).isNotEmpty()) Thread.sleep(5)
            assertTrue("retired decoder reference must be removed after worker exit", (field.get(sink) as Set<*>).isEmpty())
        } finally { sink.close(); surface.release(); texture.release() }
    }

    @Test fun lateFramesAfterCloseCannotCreateAnotherSurfaceOwner() {
        val sink = AndroidMediaSink()
        val texture = SurfaceTexture(0)
        val surface = Surface(texture)
        try {
            sink.close()
            sink.setSurface(110, surface)
            sink.onVideoFrame(110, byteArrayOf(1))
            var completed = false
            sink.detachSurface(surface) { completed = true }
            assertTrue(completed)
        } finally { surface.release(); texture.release() }
    }
}
