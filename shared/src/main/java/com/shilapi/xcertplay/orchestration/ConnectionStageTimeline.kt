package com.shilapi.xcertplay.orchestration

/** 记录状态观察之间的耗时；新连接代次重置，迟到的旧代次不能覆盖当前记录。 */
internal class ConnectionStageTimeline(private val nanoTime: () -> Long = System::nanoTime) {
    private var generation = Int.MIN_VALUE
    private var startedNs = 0L
    private var previousNs = 0L
    private var previous: String? = null

    @Synchronized fun mark(stage: String, nextGeneration: Int): String? {
        if (nextGeneration < generation) return null
        val now = nanoTime()
        if (nextGeneration != generation) {
            generation = nextGeneration; startedNs = now; previousNs = now; previous = null
        }
        if (stage == previous) return null
        val line = "Connection stage=$stage generation=$generation previous=${previous ?: "none"} " +
            "stageElapsedMs=${(now - previousNs).coerceAtLeast(0) / 1_000_000} " +
            "attemptElapsedMs=${(now - startedNs).coerceAtLeast(0) / 1_000_000}"
        previous = stage; previousNs = now
        return line
    }
}
