package com.shilapi.xcertplay.airplay

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 真实 UDP 覆盖退出、端口释放和迟到包；不依赖跨线程中断阻塞 socket。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class AudioStreamLifecycleTest {
    @Test fun idleReceiversExitAndReleaseBothSocketsAfterRepeatedClose() {
        val stream = AudioStream(ByteArray(32))
        stream.listen(object : AudioStream.Listener {})
        val sockets = listOf(socket(stream, "dataSocket"), socket(stream, "controlSocket"))
        val workers = listOf(worker(stream, "dataThread"), worker(stream, "controlThread"))
        stream.close()
        stream.close()
        workers.forEach { it.join(3_000); assertFalse("接收线程仍在运行", it.isAlive) }
        sockets.forEach { assertTrue("UDP 端口未释放", it.isClosed) }
    }

    @Test fun packetsSentAfterCloseCannotReachTheOldListener() {
        val callbacks = AtomicInteger()
        val stream = AudioStream(ByteArray(32))
        val (port, _) = stream.listen(object : AudioStream.Listener {
            override fun onPacket(wire: ByteArray, rtp: ByteArray?, sample: Int?, error: Throwable?) {
                callbacks.incrementAndGet()
            }
        })
        val receiver = worker(stream, "dataThread")
        stream.close()
        DatagramSocket().use { sender ->
            val bytes = ByteArray(12)
            sender.send(DatagramPacket(bytes, bytes.size, InetAddress.getLoopbackAddress(), port))
        }
        receiver.join(3_000)
        assertFalse(receiver.isAlive)
        assertEquals(0, callbacks.get())
        assertThrows(IllegalStateException::class.java) { stream.listen(object : AudioStream.Listener {}) }
    }

    @Test fun partialSetupFailureReleasesTheAlreadyBoundSocket() {
        val stream = AudioStream(ByteArray(32), onDiagnostic = { throw IllegalStateException("诊断回调失败") })
        assertThrows(IllegalStateException::class.java) { stream.listen(object : AudioStream.Listener {}) }
        assertTrue(socket(stream, "dataSocket").isClosed)
        stream.close()
    }

    private fun socket(stream: AudioStream, name: String): DatagramSocket =
        field(stream, name) as DatagramSocket
    private fun worker(stream: AudioStream, name: String): Thread = field(stream, name) as Thread
    private fun field(stream: AudioStream, name: String): Any = AudioStream::class.java
        .getDeclaredField(name).apply { isAccessible = true }.get(stream)
}
