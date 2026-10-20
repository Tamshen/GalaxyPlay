package com.shilapi.xcertplay.media

import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class MusicPcmWriterTest {
    @Test fun outputRetainsSamplesAndReusesTwoBlocksUnderBackpressure() {
        val output = ByteArrayOutputStream()
        val identities = Collections.newSetFromMap(IdentityHashMap<ByteArray, Boolean>())
        val finished = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        val source = ByteArray(48_000) { (it % 251).toByte() }
        val original = source.clone()
        val writer = MusicPcmWriter(48_000, 4, { data, offset, length ->
            identities.add(data)
            output.write(data, offset, length)
            if (output.size() == source.size) finished.countDown()
        }, {}, error::set)
        writer.start()
        try {
            writer.submit(source, 0, source.size)
            source.fill(0)
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            assertArrayEquals(original, output.toByteArray())
            assertTrue(identities.size <= 2)
            assertNull(error.get())
        } finally { writer.stopAndJoin() }
    }

    @Test fun stopUnblocksFullQueueAndJoinsWriterBeforeResourceRelease() {
        val writing = CountDownLatch(1)
        val done = CountDownLatch(1)
        val producerDone = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        val writer = MusicPcmWriter(48_000, 4, { _, _, _ ->
            writing.countDown()
            try { CountDownLatch(1).await() } finally { done.countDown() }
        }, {}, error::set)
        writer.start()
        val producer = Thread {
            writer.submit(ByteArray(192_000), 0, 192_000)
            producerDone.countDown()
        }.apply { start() }
        try {
            assertTrue(writing.await(2, TimeUnit.SECONDS))
            assertFalse(producerDone.await(40, TimeUnit.MILLISECONDS))
            writer.stopAndJoin()
            assertEquals(0L, done.count)
            assertTrue(producerDone.await(2, TimeUnit.SECONDS))
            assertNull(error.get())
        } finally { writer.stopAndJoin(); producer.join(2000) }
    }

    @Test fun writerFailureStopsProducerAndReportsExactlyOnce() {
        val failed = CountDownLatch(1)
        val error = IllegalStateException("write")
        val writer = MusicPcmWriter(48_000, 4, { _, _, _ -> throw error }, {}, {
            assertSame(error, it)
            failed.countDown()
        })
        writer.start()
        try {
            writer.submit(ByteArray(192_000), 0, 192_000)
            assertTrue(failed.await(2, TimeUnit.SECONDS))
        } finally { writer.stopAndJoin() }
    }
}
