package com.shilapi.xcertplay

import com.shilapi.xcertplay.network.WirelessStartupFailure

/** 官方有限重试预算由会话持有；重复失败、旧会话和一次首帧不能重新补额度。 */
internal class GalaxyWirelessRecovery {
    private val budget = WirelessStartupRetryBudget()
    private var failedGeneration = -1
    var stopped = false
        private set
    val retries get() = budget.retries
    sealed interface Decision {
        data class Retry(val delayMillis: Long) : Decision
        data object Stop : Decision
        data object Ignore : Decision
    }

    fun failed(generation: Int, failure: WirelessStartupFailure): Decision {
        if (stopped || generation <= failedGeneration) return Decision.Ignore
        failedGeneration = generation
        val delay = if (failure == WirelessStartupFailure.HOTSPOT_CONFIGURATION) null else budget.nextDelayMillis()
        if (delay == null) { stopped = true; return Decision.Stop }
        return Decision.Retry(delay)
    }
    fun firstFrame(session: Any, nowMillis: Long) = budget.firstFrame(session, nowMillis)
    fun stable(session: Any, nowMillis: Long) = budget.resetIfStable(session, nowMillis)
    fun disconnected() = budget.disconnected()
    fun manualRetry() { budget.manualRetry(); failedGeneration = -1; stopped = false }
}
