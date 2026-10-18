package com.shilapi.xcertplay

import java.io.Closeable
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** 一份快照同时供窗口、详情与日志使用；停止不等待查询，迟到结果无权发布。 */
internal class GalaxyPerformanceMonitor(
    private val read: (Boolean) -> GalaxyPerformanceInput,
    private val publish: (GalaxyPerformanceSample, List<GalaxyPerformanceSample>) -> Unit,
    private val currentOwner: () -> Any? = { CarPlayBackgroundSession.snapshot()?.controller?.activeMediaSessionOwner() },
    private val report: (String) -> Unit = L7DebugLog::record,
    private val schedule: () -> GalaxyPerformanceSchedule = { GalaxyPerformanceWorker() },
    private val clockMs: () -> Long = android.os.SystemClock::elapsedRealtime,
    private val clockNs: () -> Long = System::nanoTime,
    private val cpuNs: () -> Long = android.os.Debug::threadCpuTimeNanos,
) : Closeable {
    private class Run(val startedMs: Long) {
        val rates = GalaxyPerformanceRates()
        val history = ArrayDeque<GalaxyPerformanceSample>()
        var lastLog = startedMs
        @Volatile var deepUntil = -1L
        var wasDeep = false
        var failed = false
        var overBudget = false
        var samples = 0L
        var worker: GalaxyPerformanceSchedule? = null
    }
    private val lock = Any()
    @Volatile private var run: Run? = null
    private var closing: GalaxyPerformanceSchedule? = null
    private val display = AtomicLong()
    val running get() = run != null

    fun start(): Boolean {
        val next = synchronized(lock) {
            if (run != null) return false
            Run(clockMs()).also { run = it; display.set(0) }
        }
        event("START intervalMs=1000 pssIntervalMs=10000")
        val worker = try { schedule() } catch (_: Exception) { close(); event("START_FAILED"); return false }
        try {
            worker.start(Runnable { sample(next) })
            synchronized(lock) { if (run === next) next.worker = worker else worker.close() }
        } catch (_: Exception) { worker.close(); close(); event("START_FAILED") }
        return running
    }
    fun deep(): Boolean = synchronized(lock) {
        val active = run ?: return false
        active.deepUntil = clockMs() + 15_000
        event("MODE mode=DEEP durationMs=15000 threadLimit=128")
        true
    }
    fun displayCost(nanos: Long) { if (running && nanos >= 0) display.addAndGet(nanos) }

    private fun sample(active: Run) {
        if (run !== active) return
        val deep = clockMs() < active.deepUntil
        if (active.wasDeep && !deep) event("MODE mode=BASIC")
        active.wasDeep = deep
        val start = clockNs(); val cpuStart = runCatching(cpuNs).getOrNull()
        var failed = false
        var value = try { read(deep) } catch (_: Exception) {
            failed = true
            GalaxyPerformanceInput(clockMs(), null, null, null, null, null, null, null, null, null, emptyList(), emptyList())
        }
        if (run !== active) return
        if (value.owner != null && currentOwner() !== value.owner)
            value = value.copy(owner = null, video = emptyList(), audio = emptyList(), carPlayConnected = false)
        val cpuEnd = runCatching(cpuNs).getOrNull()
        val usedCpu = cpuEnd?.takeIf { cpuStart != null && it >= cpuStart }?.let { it - cpuStart!! }
        val sample = active.rates.sample(value, deep, clockNs() - start,
            usedCpu, display.getAndSet(0), failed)
        val history = synchronized(lock) {
            if (run !== active) return
            if (active.history.size == 60) active.history.removeFirst()
            active.history.addLast(sample); active.samples++
            active.history.toList()
        }
        if (sample.failed != active.failed) { active.failed = sample.failed; event("COUNTERS failed=${sample.failed}") }
        if (sample.overBudget != active.overBudget) { active.overBudget = sample.overBudget; event("BUDGET exceeded=${sample.overBudget}") }
        if (value.wallMs - active.lastLog >= 5000) {
            active.lastLog = value.wallMs
            event(summary(sample))
        }
        if (run === active) runCatching { publish(sample, history) }
    }

    private fun event(value: String) { runCatching { report("PERFORMANCE $value") } }
    private fun summary(s: GalaxyPerformanceSample): String {
        val p = s.input
        fun number(value: Double?) = value?.let { String.format(Locale.US, "%.1f", it) } ?: "unknown"
        val video = s.video.joinToString(",") { "${it.value.stream}:rx=${number(it.receivedFps)}:presented=${number(it.presentedFps)}:drop=${it.dropped ?: "unknown"}" }
        val audio = p.audio.joinToString(",") { "${it.role}:underruns=${it.underruns ?: "unknown"}:intervalMs=${it.intervalMs}" }
        val threads = s.threadCpu.joinToString(",") { "${it.first}:${number(it.second)}" }
        return "SAMPLE atMs=${p.wallMs} session=${s.session} mode=${if (s.deep) "DEEP" else "BASIC"} " +
            "cpuCorePercent=${number(s.cpuCorePercent)} javaBytes=${p.javaBytes ?: "unknown"} nativeBytes=${p.nativeBytes ?: "unknown"} " +
            "pssKiB=${p.pssKiB ?: "unknown"} rssKiB=${p.rssKiB ?: "unknown"} memoryAgeMs=${p.memoryAgeMs ?: "unknown"} " +
            "ufps=${number(s.uiFps)} ufpsState=${s.uiState} uiReportsLost=${p.uiFrames?.missingReports ?: "unknown"} " +
            "cfps=${number(s.main?.presentedFps)} carPlayConnected=${p.carPlayConnected} gcCount=${p.gcCount ?: "unknown"} gcTimeMs=${p.gcTimeMs ?: "unknown"} video=$video audio=$audio " +
            "queryNs=${s.sampleNs} queryCpuNs=${s.sampleCpuNs ?: "unknown"} displayNs=${s.displayNs} overBudget=${s.overBudget} threads=$threads"
    }
    override fun close() {
        val old = synchronized(lock) { run?.also { run = null } } ?: return
        old.worker?.let { closing = it; it.close() }
        event("STOP elapsedMs=${(clockMs() - old.startedMs).coerceAtLeast(0)} samples=${old.samples}")
    }
    fun awaitClosed(timeoutMs: Long): Boolean = closing?.awaitClosed(timeoutMs) ?: true
}

internal object GalaxyPerformanceState {
    @Volatile var running = false
    @Volatile var overlayWanted = true
    @Volatile var overlayVisible = false
    @Volatile var overlayPermission = false
    @Volatile var overlayBounds: android.graphics.Rect? = null
    @Volatile var latest: GalaxyPerformanceSample? = null
    @Volatile var history: List<GalaxyPerformanceSample> = emptyList()
    @Volatile var displayFailed = false
}
