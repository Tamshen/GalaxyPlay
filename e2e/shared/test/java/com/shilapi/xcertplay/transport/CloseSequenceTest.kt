package com.shilapi.xcertplay.transport

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CloseSequenceTest {
    @Test fun failuresDoNotSkipLaterCleanupAndAreAggregated() {
        val sequence = CloseSequence("test"); val calls = mutableListOf<Int>()
        sequence.step { calls += 1; throw IllegalStateException() }
        sequence.step { calls += 2; throw IOException() }
        sequence.step { calls += 3 }
        try { sequence.finish(); fail() } catch (e: IOException) { assertEquals(2, e.suppressed.size) }
        assertEquals(listOf(1, 2, 3), calls)
    }

    @Test fun timedOutWorkerStaysAFailureUntilItActuallyExits() {
        val finish = CountDownLatch(1)
        val worker = Thread {
            while (finish.count > 0) try { finish.await(20, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { }
        }
        worker.start()
        try {
            val sequence = CloseSequence("test"); sequence.await(worker, 10)
            assertTrue(worker.isAlive)
            try { sequence.finish(); fail() } catch (_: IOException) { }
        } finally { finish.countDown(); worker.join(1_000) }
        assertFalse(worker.isAlive)
        CloseSequence("test").apply { await(worker, 10); finish() }
    }

    @Test fun currentThreadIsNeverJoinedAndCannotBeReportedAsExited() {
        val sequence = CloseSequence("test"); sequence.await(Thread.currentThread(), 10)
        try { sequence.finish(); fail() } catch (_: IOException) { }
        assertTrue(Thread.interrupted())
    }

    @Test fun interruptedJoinPreservesInterruptAndContinuesCleanup() {
        val finish = CountDownLatch(1)
        val worker = Thread { try { finish.await() } catch (_: InterruptedException) { } }; worker.start()
        val sequence = CloseSequence("test")
        var cleaned = false
        Thread.currentThread().interrupt()
        try {
            sequence.await(worker, 10); sequence.step { cleaned = true }
            assertTrue(cleaned); assertTrue(Thread.currentThread().isInterrupted)
            try { sequence.finish(); fail() } catch (_: IOException) { }
        } finally { Thread.interrupted(); finish.countDown(); worker.join(1_000) }
    }
}
