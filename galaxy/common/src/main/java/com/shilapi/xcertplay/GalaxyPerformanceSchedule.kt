package com.shilapi.xcertplay

import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal interface GalaxyPerformanceSchedule : Closeable {
    fun start(task: Runnable)
    fun awaitClosed(timeoutMs: Long): Boolean
}

/** 仅显式开始后创建单个低优先级线程；固定延迟避免慢查询重叠或追赶执行。 */
internal class GalaxyPerformanceWorker : GalaxyPerformanceSchedule {
    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread({
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
            task.run()
        }, "galaxy-performance").apply { isDaemon = true }
    }
    override fun start(task: Runnable) { executor.scheduleWithFixedDelay(task, 0, 1000, TimeUnit.MILLISECONDS) }
    override fun close() { executor.shutdownNow() }
    override fun awaitClosed(timeoutMs: Long) = executor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)
}
