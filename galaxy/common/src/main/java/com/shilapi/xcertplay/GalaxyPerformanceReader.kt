package com.shilapi.xcertplay

import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import java.io.File

/** 基础每秒只读轻量计数；PSS／GC 至少十秒一次，线程扫描仅由手动限时深度模式触发。 */
internal class GalaxyPerformanceReader {
    private var memoryAt = -1L
    private var pss: Long? = null
    private var rss: Long? = null
    private var gc: Long? = null
    private var gcMs: Long? = null
    private var threadsAt = -1L

    fun read(deep: Boolean): GalaxyPerformanceInput {
        val now = SystemClock.elapsedRealtime()
        if (memoryAt < 0 || now - memoryAt >= 10_000 || now < memoryAt) {
            memoryAt = now
            pss = runCatching { Debug.MemoryInfo().also(Debug::getMemoryInfo).totalPss.toLong().takeIf { it > 0 } }.getOrNull()
            rss = runCatching {
                File("/proc/self/status").useLines { lines -> lines.firstOrNull { it.startsWith("VmRSS:") }
                    ?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull() }
            }.getOrNull()
            gc = runCatching { Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull() }.getOrNull()
            gcMs = runCatching { Debug.getRuntimeStat("art.gc.gc-time")?.toLongOrNull() }.getOrNull()
            memoryAt = SystemClock.elapsedRealtime()
        }
        val thread = if (deep && (threadsAt < 0 || now - threadsAt >= 5000 || now < threadsAt)) {
            threadsAt = now; threadTimes()
        } else null
        if (!deep) threadsAt = -1
        // 重查询完成后再对齐 CPU、视频和墙钟，避免把查询等待误算进前后帧率边界。
        val at = SystemClock.elapsedRealtime()
        val cpu = runCatching { Process.getElapsedCpuTime().takeIf { it >= 0 } }.getOrNull()
        val heap = runCatching { Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() } }.getOrNull()
        val native = runCatching { Debug.getNativeHeapAllocatedSize() }.getOrNull()
        val connection = CarPlayBackgroundSession.snapshot()
        val owner = connection?.controller?.activeMediaSessionOwner()
        val video = if (owner != null) connection.sink.videoPerformance() else emptyList()
        val audio = if (owner != null) connection.sink.audioPerformance().filter { at - it.atMs in 0..10_000 } else emptyList()
        val current = owner != null && connection?.controller?.activeMediaSessionOwner() === owner
        return GalaxyPerformanceInput(at, cpu, heap, native, pss, rss, at - memoryAt, gc, gcMs,
            if (current) owner else null, if (current) video else emptyList(), if (current) audio else emptyList(), thread)
    }

    private fun threadTimes(): Map<Int, Long>? = runCatching {
        val ticks = Os.sysconf(OsConstants._SC_CLK_TCK).also { check(it > 0) }
        File("/proc/self/task").listFiles()?.asSequence()?.take(128)?.mapNotNull { directory ->
            runCatching {
                val tid = directory.name.toInt()
                val fields = File(directory, "stat").readText().substringAfterLast(')').trim().split(' ')
                tid to ((fields[11].toLong() + fields[12].toLong()) * 1000 / ticks)
            }.getOrNull()
        }?.toMap()
    }.getOrNull()
}
