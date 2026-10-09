package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class L7VoiceInputTestTest {
    private class Access : L7VoiceInputTest.Access {
        @Volatile var permission = true
        @Volatile var occupied = false
        @Volatile var created = 0
        @Volatile var closed = 0
        @Volatile var code = 640
        var creation: (() -> Unit)? = null
        val began = CountDownLatch(1)
        val clock = AtomicLong()
        var advance = 0L
        var source = -1
        var readCount = 0
        var readResult: ((Int) -> Int)? = null
        override fun permitted() = permission
        override fun occupied() = occupied
        override fun create(source: Int): L7VoiceInputTest.Recorder {
            created++; this.source = source; creation?.invoke()
            return object : L7VoiceInputTest.Recorder {
                override fun start() { began.countDown() }
                override fun read(buffer: ByteArray): Int {
                    clock.addAndGet(advance)
                    for (i in buffer.indices step 2) { buffer[i] = 0; buffer[i + 1] = 4 }
                    return readResult?.invoke(++readCount) ?: code
                }
                override val routeType = 15
                override val silenced = false
                override fun close() { closed++ }
            }
        }
    }

    private fun await(condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < until) Thread.sleep(5)
        assertTrue("worker 未在限时内结束", condition())
    }

    @Test fun missingPermissionAndExistingUplinkNeverCreateARecorder() {
        val access = Access()
        L7VoiceInputTest(access, {}, access.clock::get).use { test ->
            access.permission = false
            assertFalse(test.start(6)); assertEquals("PERMISSION", test.snapshot.reason)
            access.permission = true; access.occupied = true
            assertFalse(test.start(6)); assertEquals("UPLINK_ACTIVE", test.snapshot.reason)
            assertEquals(0, access.created)
        }
    }

    @Test fun tenSecondCaptureOnlyKeepsEnergyAndReleasesBeforeRestart() {
        val access = Access().apply { advance = 500 }
        val logs = java.util.concurrent.CopyOnWriteArrayList<String>()
        L7VoiceInputTest(access, logs::add, access.clock::get).use { test ->
            assertTrue(test.start(6)); await { !test.snapshot.busy }
            assertEquals(L7VoiceInputTest.Phase.COMPLETE, test.snapshot.phase)
            assertEquals(10_000L, test.snapshot.elapsedMs)
            assertEquals(12_800L, test.snapshot.bytes)
            assertEquals(1024, test.snapshot.rms)
            assertEquals(1, access.closed)
            assertEquals(6, access.source)
            await { logs.any { it.contains("phase=COMPLETE") } }
            assertFalse(logs.any { it.contains("pcm=") || it.contains("transcript=") })
            assertTrue(test.start(1)); await { !test.snapshot.busy }
            assertEquals(2, access.closed)
        }
    }

    @Test fun tenSecondsOfZeroReadsFailAndKeepEvidenceWithoutChangingTheInputSource() {
        val access = Access().apply { advance = 500; code = 0 }
        val logs = java.util.concurrent.CopyOnWriteArrayList<String>()
        L7VoiceInputTest(access, logs::add, access.clock::get).use { test ->
            assertTrue(test.start(6)); await { !test.snapshot.busy }
            assertEquals(L7VoiceInputTest.Phase.FAILED, test.snapshot.phase)
            assertEquals("NO_DATA", test.snapshot.reason)
            assertEquals(0L, test.snapshot.bytes)
            assertEquals(20L, test.snapshot.reads)
            assertEquals(20L, test.snapshot.zeroReads)
            assertEquals(1, access.created); assertEquals(1, access.closed); assertEquals(6, access.source)
            await { logs.any { "phase=FAILED" in it && "reason=NO_DATA" in it && "zeroReads=20" in it } }
            access.code = 640
            assertTrue(test.start(1)); await { !test.snapshot.busy }
            assertEquals(L7VoiceInputTest.Phase.COMPLETE, test.snapshot.phase)
            assertEquals(0L, test.snapshot.zeroReads); assertEquals(2, access.closed)
        }
    }

    @Test fun initialNonblockingZeroReadsDoNotRejectALaterWorkingCapture() {
        val access = Access().apply { advance = 500; readResult = { if (it <= 5) 0 else 640 } }
        L7VoiceInputTest(access, {}, access.clock::get).use { test ->
            test.start(6); await { !test.snapshot.busy }
            assertEquals(L7VoiceInputTest.Phase.COMPLETE, test.snapshot.phase)
            assertEquals(5L, test.snapshot.zeroReads)
            assertEquals(9600L, test.snapshot.bytes)
            assertEquals(1, access.closed)
        }
    }

    @Test fun duplicateStartAndStopCannotOverlapRecorders() {
        val access = Access()
        L7VoiceInputTest(access, {}, access.clock::get).use { test ->
            assertTrue(test.start(6)); assertTrue(access.began.await(2, TimeUnit.SECONDS))
            assertFalse(test.start(1))
            test.stop(); await { !test.snapshot.busy }
            assertEquals(L7VoiceInputTest.Phase.STOPPED, test.snapshot.phase)
            assertEquals(1, access.created); assertEquals(1, access.closed)
        }
    }

    @Test fun carPlayMicrophoneTakingOverEndsLocalCapture() {
        val access = Access()
        L7VoiceInputTest(access, {}, access.clock::get).use { test ->
            test.start(6); assertTrue(access.began.await(2, TimeUnit.SECONDS))
            access.occupied = true; await { !test.snapshot.busy }
            assertEquals(L7VoiceInputTest.Phase.INTERRUPTED, test.snapshot.phase)
            assertEquals(1, access.closed)
        }
    }

    @Test fun readFailureKeepsTheErrorCodeAndReleases() {
        val access = Access().apply { code = -3 }
        L7VoiceInputTest(access, {}, access.clock::get).use { test ->
            test.start(6); await { !test.snapshot.busy }
            assertEquals(L7VoiceInputTest.Phase.FAILED, test.snapshot.phase)
            assertEquals("READ", test.snapshot.reason); assertEquals(-3, test.snapshot.code)
            assertEquals(1, access.closed)
        }
    }

    @Test fun permissionRevocationEndsCaptureAndReleases() {
        val access = Access()
        L7VoiceInputTest(access, {}, access.clock::get).use { test ->
            test.start(6); assertTrue(access.began.await(2, TimeUnit.SECONDS))
            access.permission = false; await { !test.snapshot.busy }
            assertEquals("PERMISSION_REVOKED", test.snapshot.reason)
            assertEquals(L7VoiceInputTest.Phase.FAILED, test.snapshot.phase)
            assertEquals(1, access.closed)
        }
    }

    @Test fun cancellationDuringCreationReleasesWithoutStarting() {
        val access = Access()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        access.creation = { entered.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS)) }
        val test = L7VoiceInputTest(access, {}, access.clock::get)
        try {
            test.start(6); assertTrue(entered.await(2, TimeUnit.SECONDS))
            test.close(); release.countDown(); await { !test.snapshot.busy }
            assertEquals(1, access.closed); assertEquals(1L, access.began.count)
            assertFalse(test.start(6))
        } finally { release.countDown(); test.close() }
    }

    @Test fun recorderCreationFailureDoesNotLogTheExceptionMessage() {
        val access = Access().apply { creation = { throw SecurityException("private speech and endpoint") } }
        val logs = java.util.concurrent.CopyOnWriteArrayList<String>()
        L7VoiceInputTest(access, logs::add, access.clock::get).use { test ->
            test.start(6); await { !test.snapshot.busy }
            assertEquals("SecurityException", test.snapshot.reason)
            assertFalse(logs.any { it.contains("private") || it.contains("endpoint") })
        }
    }
}
