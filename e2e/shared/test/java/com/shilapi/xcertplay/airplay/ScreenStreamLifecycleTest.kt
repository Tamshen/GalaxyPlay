package com.shilapi.xcertplay.airplay

import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class ScreenStreamLifecycleTest {
    @Test fun closeBeforeListenRejectsAndRepeatedCloseIsSafe() {
        val stream = ScreenStream(ByteArray(32))
        stream.close(); stream.close()
        assertThrows(IllegalStateException::class.java) { stream.listen(object : ScreenStream.Listener {}) }
    }

    @Test fun closeWhileAcceptedSocketIsBeingPublishedClosesLateSocket() {
        val accepted = CountDownLatch(1)
        val publish = CountDownLatch(1)
        var socket: Socket? = null
        val stream = ScreenStream(ByteArray(32), acceptConnection = { server ->
            server.accept().also {
                socket = it
                accepted.countDown()
                var done = false
                while (!done) try { done = publish.await(3, TimeUnit.SECONDS) } catch (_: InterruptedException) { }
            }
        })
        val port = stream.listen(object : ScreenStream.Listener {})
        Socket("127.0.0.1", port).use { peer ->
            try {
                assertTrue(accepted.await(3, TimeUnit.SECONDS))
                stream.close()
            } finally { publish.countDown() }
            val field = ScreenStream::class.java.getDeclaredField("thread").apply { isAccessible = true }
            val worker = field.get(stream) as Thread
            worker.join(3_000)
            assertFalse(worker.isAlive)
            assertTrue(socket!!.isClosed)
            peer.soTimeout = 1_000
            assertEquals(-1, peer.getInputStream().read())
        }
        stream.close()
    }
}
