package com.shilapi.xcertplay.media

import java.util.concurrent.atomic.AtomicLong

/** 只调整当前音乐会话的恢复水位；设置不变，容量之外的长中断不无限扩容。 */
internal class MusicJitterRecovery {
    private val largestGap = AtomicLong()
    val gapMillis get() = largestGap.get()
    fun observe(gapMillis: Long) {
        if (gapMillis > 0) largestGap.accumulateAndGet(gapMillis, ::maxOf)
    }

    fun threshold(currentBytes: Int, capacityBytes: Int, bytesPerSecond: Int, frameBytes: Int): Int {
        val gap = gapMillis
        if (gap == 0L || capacityBytes <= 0 || bytesPerSecond <= 0) return currentBytes
        val desired = bytesPerSecond.toLong() * (gap.coerceAtMost(5000) + 50) / 1000
        val ceiling = (capacityBytes - minOf(2048, capacityBytes / 2)).coerceAtLeast(0)
        val aligned = (minOf(desired, ceiling.toLong()) / frameBytes * frameBytes).toInt()
        return maxOf(currentBytes, aligned)
    }
}
