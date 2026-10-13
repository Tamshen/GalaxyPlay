package com.shilapi.xcertplay.transport

/** 原生热点首次请求无响应时复用蓝牙控制链路；不延长总期限，不新增线程。 */
class WirelessStartSessionRecovery(
    private val stillWaiting: () -> Boolean,
    private val delayMillis: Long = 5_000,
    private val nowNanos: () -> Long = System::nanoTime,
) {
    private var firstStartNanos: Long? = null
    private var consumed = false

    init { require(delayMillis in 1..30_000) }

    fun startSessionSent() {
        if (consumed) return
        if (firstStartNanos == null) firstStartNanos = nowNanos() else cancel()
    }

    fun cancel() { consumed = true }

    fun pollTimeout(remainingMillis: Long): Long {
        if (!pending()) return remainingMillis
        return minOf(remainingMillis, untilRetryMillis().coerceAtLeast(1))
    }

    fun retryIfDue(send: () -> Unit): Boolean {
        if (!pending() || untilRetryMillis() > 0) return false
        // 先消耗单次预算，发送异常或重入都不能形成重复请求循环。
        consumed = true
        send()
        return true
    }

    private fun pending(): Boolean {
        if (consumed || firstStartNanos == null) return false
        if (Thread.currentThread().isInterrupted || !stillWaiting()) { cancel(); return false }
        return true
    }

    private fun untilRetryMillis(): Long {
        val elapsed = (nowNanos() - firstStartNanos!!).coerceAtLeast(0)
        return delayMillis - elapsed / 1_000_000
    }
}
