package com.shilapi.xcertplay.media

/** worker 持有：四次快速尝试后留两次冷却探测，不因重复配置或单帧输出无限补额度。 */
internal class VideoRecoveryGate(
    private val maxFailures: Int = 4,
    private val stableNs: Long = 10_000_000_000L,
    private val cooldownNs: Long = 15_000_000_000L,
    private val maxProbes: Int = 2,
    private val outputGapNs: Long = 1_000_000_000L,
) {
    var failures = 0
        private set
    var probes = 0
        private set
    var retryAtNs = 0L
        private set
    private var healthySinceNs: Long? = null
    private var lastOutputNs: Long? = null
    val exhausted: Boolean get() = failures >= maxFailures
    val terminal: Boolean get() = exhausted && probes >= maxProbes

    fun canRetry(nowNs: Long): Boolean = !terminal && nowNs >= retryAtNs

    /** 只有真正进入创建阶段才消耗探测机会，检查状态和请求关键帧不计数。 */
    fun beginAttempt(nowNs: Long): Boolean {
        if (!canRetry(nowNs)) return false
        if (exhausted) { probes++; failures = maxFailures - 1 }
        return true
    }

    fun onFailure(nowNs: Long) {
        healthySinceNs = null; lastOutputNs = null
        failures = (failures + 1).coerceAtMost(maxFailures)
        retryAtNs = nowNs + if (exhausted) cooldownNs
            else (250_000_000L shl (failures - 1).coerceIn(0, 3))
    }

    fun onOutput(nowNs: Long) {
        val previous = lastOutputNs
        if (previous == null || nowNs - previous > outputGapNs) healthySinceNs = nowNs
        lastOutputNs = nowNs
        val since = healthySinceNs ?: nowNs
        if (nowNs - since >= stableNs) reset()
    }

    fun reset() { failures = 0; probes = 0; retryAtNs = 0; healthySinceNs = null; lastOutputNs = null }
}
