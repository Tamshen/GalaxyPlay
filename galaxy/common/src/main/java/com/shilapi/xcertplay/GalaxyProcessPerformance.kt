package com.shilapi.xcertplay

/** 有日志活动时每五秒读一次应用 CPU／堆计数，不创建线程、不读设备身份或其他应用。 */
internal class GalaxyProcessPerformance(
    private val clock: () -> Long = android.os.SystemClock::elapsedRealtime,
    private val cpuMillis: () -> Long = android.os.Process::getElapsedCpuTime,
    private val heapBytes: () -> Long = { Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() } },
) {
    private var lastWall = -1L
    private var lastCpu = 0L

    @Synchronized fun poll(): String? {
        val now = clock()
        if (lastWall >= 0 && now >= lastWall && now - lastWall < 5000) return null
        val cpu = cpuMillis()
        val elapsed = now - lastWall
        val used = cpu - lastCpu
        val valid = lastWall >= 0 && elapsed > 0 && used >= 0
        lastWall = now
        lastCpu = cpu
        if (!valid) return null
        // 按一颗核心计算百分比，多线程可超过 100%；不是整机 CPU 使用率。
        return "PERFORMANCE elapsedMs=$elapsed processCpuMs=$used cpuCorePercent=${used * 100 / elapsed} " +
            "heapUsedMiB=${heapBytes().coerceAtLeast(0) / (1024 * 1024)}"
    }
}
