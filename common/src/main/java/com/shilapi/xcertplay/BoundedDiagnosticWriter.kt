package com.shilapi.xcertplay

import java.io.Closeable
import java.util.ArrayDeque

/** 单个后台写入线程，最多保留 maxPending 条待写记录及一条正在写入的记录。 */
internal class BoundedDiagnosticWriter<T : Any>(
    private val maxPending: Int = 64,
    private val write: (T) -> Unit,
) : Closeable {
    private val monitor = Object()
    private val pending = ArrayDeque<T>()
    private var closed = false
    private var active = false
    private var dropped = 0L

    init {
        require(maxPending > 0)
        Thread(::drain, "diplay-diagnostic-writer").apply {
            isDaemon = true
            start()
        }
    }

    /** 不等待磁盘；队列已满时丢弃最旧的待写记录。 */
    fun enqueue(entry: T): Boolean = synchronized(monitor) {
        if (closed) return@synchronized false
        if (pending.size == maxPending) {
            pending.removeFirst()
            dropped++
        }
        pending.addLast(entry)
        monitor.notifyAll()
        true
    }

    internal val pendingCount: Int get() = synchronized(monitor) { pending.size }
    internal val droppedCount: Long get() = synchronized(monitor) { dropped }

    private fun drain() {
        while (true) {
            val next = synchronized(monitor) {
                while (pending.isEmpty() && !closed) monitor.wait()
                if (pending.isEmpty()) return
                active = true
                pending.removeFirst()
            }
            // 磁盘或诊断回调失败不能阻止后续记录。
            try {
                runCatching { write(next) }
            } finally {
                synchronized(monitor) {
                    active = false
                    monitor.notifyAll()
                }
            }
        }
    }

    /** 仅供非传输、非 UI 线程限时等待写入，以及确定性测试。 */
    fun awaitIdle(timeoutMillis: Long): Boolean = synchronized(monitor) {
        val deadline = System.nanoTime() + timeoutMillis.coerceAtLeast(0) * 1_000_000L
        while (active || pending.isNotEmpty()) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return@synchronized false
            monitor.wait(remaining / 1_000_000L, (remaining % 1_000_000L).toInt())
        }
        true
    }

    /** 停止接收新记录，后台继续写完队列，调用方无需等待。 */
    override fun close() = synchronized(monitor) {
        closed = true
        monitor.notifyAll()
    }
}
