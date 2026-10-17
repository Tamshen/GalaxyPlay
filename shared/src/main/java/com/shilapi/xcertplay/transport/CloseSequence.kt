package com.shilapi.xcertplay.transport

import java.io.IOException

/** 关闭步骤互不短路；调用者在驱动／状态锁外等待，并把汇总失败交给原资源门禁。 */
internal class CloseSequence(private val component: String) {
    private val failures = mutableListOf<Throwable>()
    fun step(action: () -> Unit) {
        try { action() } catch (error: Throwable) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            failures.add(error)
        }
    }
    fun await(thread: Thread, timeoutMillis: Long) {
        require(timeoutMillis > 0)
        if (thread !== Thread.currentThread()) step { thread.join(timeoutMillis) }
        if (thread.isAlive) {
            step { thread.interrupt() }
            failures.add(IOException("$component worker still closing"))
        }
    }
    fun finish() {
        if (failures.isNotEmpty()) throw IOException("$component close incomplete").also { error ->
            failures.forEach(error::addSuppressed)
        }
    }
}
