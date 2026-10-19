package com.shilapi.xcertplay.transport

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** USB验证 角色切换事务；基础管道先关闭，随后恢复本次拥有的 gadget 配置。 */
internal class GalaxyUsbVerificationAttempt(
    private val platform: Platform,
    private val phone: Phone,
    private val cancelled: () -> Boolean,
    private val report: (String) -> Unit,
) : Closeable {
    interface Platform : AutoCloseable {
        fun prepare()
        fun checkpoint()
        fun activate()
        fun awaitConfigured(cancelled: () -> Boolean)
        fun openStream(): BlockingDuplexByteStream
        fun restore()
        override fun close() {}
    }
    interface Phone {
        fun capability(): Boolean
        fun switchRole(): Int
        fun close()
    }
    private val lock = Any()
    private val closed = AtomicBoolean(false)
    private var changed = false
    private var started = false
    private var stream: BlockingDuplexByteStream? = null

    fun open(): BlockingDuplexByteStream = synchronized(lock) {
        check(!started) { "USB验证 attempt already started" }
        started = true
        try {
            step("PREFLIGHT") { platform.prepare() }
            step("PHONE_CAPABILITY") {
                if (!phone.capability()) throw GalaxyUsbVerificationFailure(GalaxyUsbVerificationFailure.Reason.PHONE_CAPABILITY_REJECTED)
            }
            current()
            platform.checkpoint()
            changed = true // 指令失败也可能已改变手机／系统；在第一个写操作前登记恢复。
            step("PHONE_ROLE_REQUEST") {
                val result = phone.switchRole()
                emit("stage=PHONE_ROLE_REPLY returned=$result expected=0 request=0x51")
                if (result != 0) throw GalaxyUsbVerificationFailure(GalaxyUsbVerificationFailure.Reason.PHONE_ROLE_REJECTED)
            }
            phone.close()
            step("GADGET_CONFIG") { platform.activate() }
            step("GADGET_WAIT") { platform.awaitConfigured { closed.get() || cancelled() } }
            step("IPOD_OPEN") { stream = platform.openStream() }
            current()
            requireNotNull(stream)
        } catch (error: Exception) {
            val original = error as? GalaxyUsbVerificationFailure ?: GalaxyUsbVerificationFailure(GalaxyUsbVerificationFailure.Reason.NODE_OPEN_FAILED)
            emit("stage=FAILED reason=${original.reason}")
            try { close() } catch (_: Exception) {
                throw GalaxyUsbVerificationFailure(GalaxyUsbVerificationFailure.Reason.RESTORE_FAILED)
            }
            throw original
        } finally { try { phone.close() } catch (_: Exception) { emit("stage=PHONE_CLOSE result=FAILED") } }
    }

    override fun close() {
        closed.set(true)
        synchronized(lock) {
            var failure: Exception? = null
            try { stream?.close() } catch (e: Exception) { failure = e }
            stream = null
            if (changed) {
                emit("stage=RESTORE result=BEGIN")
                try {
                    platform.restore()
                    changed = false
                    emit("stage=RESTORE result=COMPLETED")
                } catch (e: Exception) {
                    emit("stage=RESTORE result=FAILED")
                    failure = e
                } finally {
                    // 恢复失败由持久化记录和下一任租约处理，旧对象不得再次覆盖新会话。
                    changed = false
                }
            }
            platform.close()
            if (failure != null) throw GalaxyUsbVerificationFailure(GalaxyUsbVerificationFailure.Reason.RESTORE_FAILED)
        }
    }

    private fun current() {
        if (closed.get() || cancelled() || Thread.currentThread().isInterrupted)
            throw GalaxyUsbVerificationFailure(GalaxyUsbVerificationFailure.Reason.CANCELLED)
    }
    private fun step(stage: String, action: () -> Unit) {
        current()
        val begin = System.nanoTime()
        emit("stage=$stage result=BEGIN")
        action()
        emit("stage=$stage result=COMPLETED elapsedMs=${(System.nanoTime() - begin) / 1_000_000}")
        current()
    }
    private fun emit(body: String) { try { report("USB_VERIFY $body") } catch (_: Exception) { } }
}
