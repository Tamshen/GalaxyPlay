package com.shilapi.xcertplay.media

/** 仅手动检测期间更新音频的附加快照，既有音频日志始终保持。 */
object GalaxyPerformanceObservation { @Volatile var active = false }

/** 单调累计计数供低频观察，不消耗既有五秒诊断窗口。epoch 只标识本进程的解码器。 */
data class GalaxyVideoPerformance(
    val stream: Int, val epoch: Long,
    val received: Long, val input: Long, val submitted: Long, val presented: Long, val dropped: Long,
    val queueFrames: Int, val queueBytes: Long,
    val codec: String?, val outputWidth: Int, val outputHeight: Int, val negotiatedFps: Int,
)

/** 复用音频已有统计读取；时间和窗口明示，未知值不伪造为零。 */
data class GalaxyAudioPerformance(
    val role: String, val codec: String, val atMs: Long, val intervalMs: Long,
    val underruns: Int?, val queuePackets: Int,
)
