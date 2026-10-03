package com.shilapi.xcertplay.media

/** 由解码 worker 持有。连续失败有限重试，稳定输出后恢复预算，不阻塞线程退避。 */
internal class VideoRecoveryGate(
    private val maxFailures: Int = 4,
    private val stableNs: Long = 10_000_000_000L,
) {
    var failures = 0
        private set
    var retryAtNs = 0L
        private set
    private var healthySinceNs: Long? = null
    val exhausted: Boolean get() = failures >= maxFailures

    fun canRetry(nowNs: Long): Boolean = !exhausted && nowNs >= retryAtNs

    fun onFailure(nowNs: Long) {
        healthySinceNs = null
        failures++
        retryAtNs = nowNs + (250_000_000L shl (failures - 1).coerceIn(0, 3))
    }

    fun onOutput(nowNs: Long) {
        val since = healthySinceNs
        if (since == null) healthySinceNs = nowNs
        else if (nowNs - since >= stableNs) reset()
    }

    fun reset() { failures = 0; retryAtNs = 0; healthySinceNs = null }
}
