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

    fun failed(generation: Int, failure: WirelessStartupFailure?): Decision {
        if (stopped || generation <= failedGeneration) return Decision.Ignore
        failedGeneration = generation
        val delay = if (failure == WirelessStartupFailure.HOTSPOT_CONFIGURATION) null else budget.nextDelayMillis()
        if (delay == null) { stopped = true; return Decision.Stop }
        // 无线重试最多五次；蓝牙或会话中断也进入同一预算，避免无分类错误无限重连。
        return Decision.Retry((delay / 2).coerceAtMost(5_000L))
    }
    fun firstFrame(session: Any, nowMillis: Long) = budget.firstFrame(session, nowMillis)
    fun stable(session: Any, nowMillis: Long) = budget.resetIfStable(session, nowMillis)
    fun disconnected() = budget.disconnected()
    fun stop() { stopped = true; budget.disconnected() }
    fun manualRetry() { budget.manualRetry(); failedGeneration = -1; stopped = false }
}
