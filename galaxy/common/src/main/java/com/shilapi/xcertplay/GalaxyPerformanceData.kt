package com.shilapi.xcertplay

import com.shilapi.xcertplay.media.GalaxyVideoPerformance
import com.shilapi.xcertplay.media.GalaxyAudioPerformance

/** 身份只在内存比较，公开的摘要只含进程数值与本次检测的顺序编号。 */
internal data class GalaxyPerformanceInput(
    val wallMs: Long, val cpuMs: Long?, val javaBytes: Long?, val nativeBytes: Long?,
    val pssKiB: Long?, val rssKiB: Long?, val memoryAgeMs: Long?, val gcCount: Long?, val gcTimeMs: Long?,
    val owner: Any?, val video: List<GalaxyVideoPerformance>, val audio: List<GalaxyAudioPerformance>,
    val threads: Map<Int, Long>? = null,
)

internal data class GalaxyVideoRate(
    val value: GalaxyVideoPerformance, val receivedFps: Double?, val presentedFps: Double?, val dropped: Long?,
)

internal data class GalaxyPerformanceSample(
    val input: GalaxyPerformanceInput, val cpuCorePercent: Double?, val video: List<GalaxyVideoRate>,
    val session: Long, val deep: Boolean, val sampleNs: Long, val sampleCpuNs: Long?,
    val displayNs: Long, val threadCpu: List<Pair<Int, Double>>, val failed: Boolean,
) {
    val main get() = video.firstOrNull { it.value.stream == 110 }
    val overBudget get() = sampleNs > 20_000_000 || displayNs > 8_000_000
}

/** 首样本、计数回退、旧会话与缺少呈现证据都返回未知；读取不修改源计数。 */
internal class GalaxyPerformanceRates {
    private var previous: GalaxyPerformanceInput? = null
    private var session = 0L
    private var threadPrevious: GalaxyPerformanceInput? = null
    fun reset() { previous = null; threadPrevious = null; session = 0 }
    fun sample(input: GalaxyPerformanceInput, deep: Boolean, costNs: Long, cpuNs: Long?,
               displayNs: Long, failed: Boolean): GalaxyPerformanceSample {
        val old = previous
        val elapsed = old?.let { input.wallMs - it.wallMs } ?: 0
        val same = old != null && old.owner === input.owner && elapsed > 0
        if (old?.owner !== input.owner && input.owner != null) session++
        fun rate(value: Long, before: Long?): Double? = if (elapsed > 0 && before != null && value >= before)
            (value - before) * 1000.0 / elapsed else null
        val cpu = input.cpuMs?.let { value -> rate(value, old?.cpuMs)?.div(10) }
        val videos = input.video.map { value ->
            val before = if (same) old?.video?.firstOrNull { it.stream == value.stream && it.epoch == value.epoch } else null
            GalaxyVideoRate(value, rate(value.received, before?.received),
                if (value.presented > 0) rate(value.presented, before?.presented) else null,
                before?.takeIf { value.dropped >= it.dropped }?.let { value.dropped - it.dropped })
        }
        val threads = input.threads?.let { current ->
            val before = threadPrevious
            val millis = before?.let { input.wallMs - it.wallMs } ?: 0
            threadPrevious = input
            if (before == null || millis <= 0) emptyList() else current.mapNotNull { (tid, used) ->
                before.threads?.get(tid)?.takeIf { used >= it }?.let { tid to (used - it) * 100.0 / millis }
            }.sortedByDescending { it.second }.take(10)
        }.orEmpty()
        if (!deep) threadPrevious = null
        previous = input
        // 历史只保存匿名数值，不能通过采样快照延长手机会话与媒体缓冲的生命期。
        return GalaxyPerformanceSample(input.copy(owner = null), cpu, videos, session, deep, costNs, cpuNs, displayNs, threads, failed)
    }
}
