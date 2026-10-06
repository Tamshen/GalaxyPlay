package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.VideoCodec
import org.junit.Assert.*
import org.junit.Test

class VideoDecodeQueueTest {
    @Test fun resyncDiscardsPicturesButRetainsManualRetryAndSurfaceCompletionInOrder() {
        val queue = VideoDecodeQueue()
        val surface = VideoJob.SurfaceChanged(null)
        val config = VideoJob.Config(VideoCodec.H264, byteArrayOf(1))
        queue.offer(VideoJob.Frame(byteArrayOf(1)))
        queue.offer(VideoJob.Retry)
        queue.offer(VideoJob.Resync)
        queue.offer(surface)
        queue.offer(config)
        assertEquals(1, queue.discardFrames())
        assertEquals(VideoJob.Retry, queue.poll(0))
        assertSame(surface, queue.poll(0))
        assertSame(config, queue.poll(0))
        assertNull(queue.poll(0))
    }

    @Test fun lostReferenceChainWaitsForSuccessfullyQueuedKeyframe() {
        val chain = VideoReferenceChain()
        val predicted = byteArrayOf(0, 0, 0, 1, 0x41, 1)
        val idr = byteArrayOf(0, 0, 0, 1, 0x65, 1)
        assertFalse(chain.accepts(predicted, VideoCodec.H264))
        assertTrue(chain.accepts(idr, VideoCodec.H264))
        assertTrue(chain.needsKeyFrame) // Receiving it is insufficient if the codec is still busy.
        chain.onQueued()
        assertTrue(chain.accepts(predicted, VideoCodec.H264))
        chain.reset()
        assertFalse(chain.accepts(predicted, VideoCodec.H264))
    }

    @Test fun overflowPreservesConfigurationAndResetsBeforeNewReferenceChain() {
        val queue = VideoDecodeQueue(maxFrames = 2)
        val config = VideoJob.Config(VideoCodec.H264, byteArrayOf(1))
        val surface = VideoJob.SurfaceChanged(null)
        queue.offer(config)
        queue.offer(VideoJob.Frame(byteArrayOf(1)))
        queue.offer(surface)
        queue.offer(VideoJob.Frame(byteArrayOf(2)))
        queue.offer(VideoJob.Frame(byteArrayOf(3)))
        assertSame(config, queue.poll(0))
        assertSame(surface, queue.poll(0))
        assertEquals(VideoJob.Resync, queue.poll(0))
        assertArrayEquals(byteArrayOf(3), (queue.poll(0) as VideoJob.Frame).nalus)
        assertNull(queue.poll(0))
    }

    @Test fun byteBudgetAlsoTriggersRecoveryAndRejectsOversizedFrame() {
        val queue = VideoDecodeQueue(maxFrames = 8, maxBytes = 5)
        queue.offer(VideoJob.Frame(ByteArray(3)))
        queue.offer(VideoJob.Frame(ByteArray(3)))
        assertEquals(VideoJob.Resync, queue.poll(0))
        assertEquals(3, (queue.poll(0) as VideoJob.Frame).nalus.size)
        queue.offer(VideoJob.Frame(ByteArray(6)))
        assertEquals(VideoJob.Resync, queue.poll(0))
        assertNull(queue.poll(0))
    }

    @Test fun fullOutputMustBeDrainedWhileRetryingTheSameInput() {
        var heldOutputs = 2
        var dequeues = 0
        val submitted = mutableListOf<Int>()
        for (frame in 1..3) {
            val index = VideoInputPump.acquire(
                running = { true },
                drain = { if (heldOutputs > 0) heldOutputs-- },
                dequeue = { dequeues++; if (heldOutputs > 0) -1 else 0 },
            )
            assertEquals(0, index)
            submitted.add(frame)
            heldOutputs = 2
        }
        assertEquals(listOf(1, 2, 3), submitted)
        assertEquals(6, dequeues)
    }

    @Test fun stalledDecoderHasFiniteWaitAndShutdownCancelsImmediately() {
        var time = 0L
        var attempts = 0
        assertEquals(-1, VideoInputPump.acquire(
            running = { true }, drain = {}, dequeue = { attempts++; -1 },
            nanoTime = { time.also { time += 10 } }, timeoutNs = 30,
        ))
        assertEquals(2, attempts)
        assertEquals(-1, VideoInputPump.acquire(running = { false }, drain = { fail() }, dequeue = { fail(); 0 }))
    }

    @Test fun pollingReturnsFrameBudgetBeforeTheNextBurst() {
        val queue = VideoDecodeQueue(maxFrames = 2, maxBytes = 6)
        queue.offer(VideoJob.Frame(ByteArray(3)))
        queue.offer(VideoJob.Frame(ByteArray(3)))
        queue.poll(0)
        assertEquals(1, queue.queuedFrames)
        assertEquals(3L, queue.queuedBytes)
        assertEquals(0, queue.offer(VideoJob.Frame(ByteArray(3))))
        assertTrue(queue.poll(0) is VideoJob.Frame)
        assertTrue(queue.poll(0) is VideoJob.Frame)
        assertEquals(0, queue.queuedFrames)
        assertEquals(0L, queue.queuedBytes)
    }

    @Test fun aNearlyExpiredFrameCannotWaitAnotherFiveHundredMillis() {
        var now = 240_000_000L
        var waits = 0
        val remaining = VideoFrameBudget.remainingNs(0, now)
        assertEquals(10_000_000L, remaining)
        assertEquals(-1, VideoInputPump.acquire(
            running = { true }, drain = {},
            dequeue = { waits++; now += 5_000_000; -1 },
            nanoTime = { now }, timeoutNs = remaining,
        ))
        assertEquals(2, waits)
        assertEquals(250_000_000L, now)
        assertEquals(0L, VideoFrameBudget.remainingNs(0, now))
    }

    @Test fun anExpiredFrameDoesNotEvenTryAnInputSlot() {
        assertEquals(-1, VideoInputPump.acquire(
            running = { true }, drain = { fail() }, dequeue = { fail(); 0 }, timeoutNs = 0,
        ))
    }

    @Test fun shutdownDrainRetainsEverySurfaceCompletionAfterOverflow() {
        var completed = 0
        val queue = VideoDecodeQueue(maxFrames = 1)
        queue.offer(VideoJob.SurfaceChanged(null) { completed++ })
        queue.offer(VideoJob.Frame(byteArrayOf(1)))
        queue.offer(VideoJob.SurfaceChanged(null) { completed++ })
        queue.offer(VideoJob.Frame(byteArrayOf(2)))
        queue.drain().filterIsInstance<VideoJob.SurfaceChanged>().forEach { it.onApplied() }
        assertEquals(2, completed)
        assertEquals(0, queue.queuedFrames)
        assertEquals(0L, queue.queuedBytes)
        assertNull(queue.poll(0))
    }

    @Test fun anOfferWakesTheWaitingWorkerWithoutPollingDelay() {
        val queue = VideoDecodeQueue()
        val ready = java.util.concurrent.CountDownLatch(1)
        val completed = java.util.concurrent.CountDownLatch(1)
        var result: VideoJob? = null
        val worker = Thread {
            ready.countDown()
            result = queue.poll(1000)
            completed.countDown()
        }.apply { isDaemon = true; start() }
        assertTrue(ready.await(1, java.util.concurrent.TimeUnit.SECONDS))
        val frame = VideoJob.Frame(byteArrayOf(1))
        queue.offer(frame)
        assertTrue(completed.await(1, java.util.concurrent.TimeUnit.SECONDS))
        assertSame(frame, result)
        worker.join(100)
    }
}
