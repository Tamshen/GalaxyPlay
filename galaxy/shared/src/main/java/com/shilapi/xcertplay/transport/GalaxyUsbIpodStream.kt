package com.shilapi.xcertplay.transport

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.FileDescriptor
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** USB验证 CUSBHostMode 同样以 O_RDWR|O_NONBLOCK 打开 ipodout0；不引入原厂协议库。 */
internal class GalaxyUsbIpodStream private constructor(
    private val fd: FileDescriptor,
    private val report: (String) -> Unit,
) : BlockingDuplexByteStream {
    private val closed = AtomicBoolean(false)
    private val ioLock = Any()
    private var reads = 0L
    private var writes = 0L
    private var rx = 0L
    private var tx = 0L
    private var released = false

    override fun send(data: ByteArray) {
        require(data.size <= 1_048_576)
        val deadline = System.nanoTime() + 2_000_000_000L
        var offset = 0
        while (offset < data.size) {
            if (System.nanoTime() >= deadline) throw IOException("USB_VERIFY_WRITE_TIMEOUT")
            synchronized(ioLock) {
                current()
                if (poll(OsConstants.POLLOUT, 50)) {
                    val count = io { Os.write(fd, data, offset, data.size - offset) } ?: 0
                    if (count > 0) {
                        if (writes++ == 0L) emit("stage=FIRST_TX bytes=$count")
                        tx += count; offset += count
                    }
                }
            }
        }
    }

    override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
        require(maxBytes > 0 && timeoutMillis >= 0)
        val deadline = System.nanoTime() + timeoutMillis.coerceAtMost(300_000) * 1_000_000L
        val bytes = ByteArray(maxBytes.coerceAtMost(65_536))
        do {
            synchronized(ioLock) {
                if (closed.get()) return ByteArray(0)
                val remaining = ((deadline - System.nanoTime()) / 1_000_000).coerceIn(0, 50).toInt()
                if (poll(OsConstants.POLLIN, remaining)) {
                    val count = io { Os.read(fd, bytes, 0, bytes.size) }
                    if (count != null) {
                        if (count == 0) return ByteArray(0)
                        if (reads++ == 0L) emit("stage=FIRST_RX bytes=$count")
                        rx += count
                        return bytes.copyOf(count)
                    }
                }
            }
        } while (System.nanoTime() < deadline)
        return null
    }

    private fun poll(event: Int, timeout: Int): Boolean {
        val entry = StructPollfd().also { it.fd = fd; it.events = event.toShort() }
        val count = io { Os.poll(arrayOf(entry), timeout) } ?: return false
        if (count == 0) return false
        if (entry.revents.toInt() and (OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL) != 0)
            throw IOException("USB_VERIFY_NODE_DISCONNECTED")
        return entry.revents.toInt() and event != 0
    }

    private fun io(operation: () -> Int): Int? = try { operation() } catch (error: ErrnoException) {
        if (error.errno == OsConstants.EAGAIN || error.errno == OsConstants.EINTR) null else {
            emit("stage=NODE_IO result=FAILED errno=${error.errno}")
            throw IOException("USB_VERIFY_NODE_IO_FAILED")
        }
    }
    private fun current() { if (closed.get()) throw IOException("USB_VERIFY_NODE_CLOSED") }
    override fun close() {
        closed.set(true)
        synchronized(ioLock) {
            if (!released) {
                released = true
                Os.close(fd)
                emit("stage=NODE_CLOSE reads=$reads writes=$writes rxBytes=$rx txBytes=$tx")
            }
        }
    }
    private fun emit(body: String) { try { report("USB_VERIFY $body") } catch (_: Exception) { } }
    companion object {
        fun open(report: (String) -> Unit): GalaxyUsbIpodStream = try {
            GalaxyUsbIpodStream(Os.open("/dev/ipodout0", OsConstants.O_RDWR or OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC, 0), report)
        } catch (e: ErrnoException) {
            try { report("USB_VERIFY stage=IPOD_OPEN result=FAILED errno=${e.errno}") } catch (_: Exception) { }
            throw GalaxyUsbVerificationFailure(GalaxyUsbVerificationFailure.Reason.NODE_OPEN_FAILED)
        }
    }
}
