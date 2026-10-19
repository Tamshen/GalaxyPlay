package com.shilapi.xcertplay.airplay

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 合成加密 UDP 验证实际接收线程会产生缺包及分阶段日志，不连接手机。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class AudioStreamDiagnosticsTest {
    @Test fun encryptedUdpSequenceGapAndProcessingStagesReachTheExportedDiagnosticCallback() {
        val key = ByteArray(32) { 7 }
        val logs = CopyOnWriteArrayList<String>()
        val first = CountDownLatch(1)
        val received = CountDownLatch(2)
        val stream = AudioStream(key, 102, logs::add)
        val (port, _) = stream.listen(object : AudioStream.Listener {
            override fun onRtp(rtp: ByteArray, sample: Int) {
                first.countDown()
                received.countDown()
            }
        })
        val worker = AudioStream::class.java.getDeclaredField("dataThread").apply { isAccessible = true }.get(stream) as Thread
        try {
            DatagramSocket().use { sender ->
                fun send(sequence: Int) {
                    val header = ByteBuffer.allocate(12).put(0x80.toByte()).put(96.toByte())
                        .putShort(sequence.toShort()).putInt(sequence * 1024).putInt(0).array()
                    val tail = ByteArray(8).also { it[0] = sequence.toByte() }
                    val nonce = ByteArray(12).also { tail.copyInto(it, 4) }
                    val sealed = AirPlayCrypto.chachaSeal(key, nonce, byteArrayOf(1, 2, 3), header.copyOfRange(4, 12))
                    val bytes = header + sealed + tail
                    sender.send(DatagramPacket(bytes, bytes.size, InetAddress.getLoopbackAddress(), port))
                }
                send(1); assertTrue(first.await(3, TimeUnit.SECONDS))
                send(20); assertTrue(received.await(3, TimeUnit.SECONDS))
            }
        } finally { stream.close(); worker.join(3000) }
        assertFalse(worker.isAlive)
        assertTrue(logs.any { it.startsWith("ReceiveGap: audio type=102 monoMs=") && "missing=18" in it })
        val summary = logs.single { it.startsWith("Receive: audio") }
        assertTrue(summary.contains("seqForwardGaps=18"))
        assertTrue(summary.contains("decryptCalls=2"))
        assertTrue(summary.contains("dispatchCalls=2"))
        assertFalse(logs.any { "payloadHead=" in it || "wire=" in it })
    }
}
