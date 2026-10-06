package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.AirPlayListenerIdentity
import com.shilapi.xcertplay.airplay.AirPlayTcpAccepted
import com.shilapi.xcertplay.network.WirelessStartupPolicy

internal class FirstTcpWatchdog(
    val listener: AirPlayListenerIdentity,
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val onTimeout: () -> Unit,
    private val nowNanos: () -> Long = System::nanoTime,
    private val timeoutMillis: Long = WirelessStartupPolicy.FIRST_TCP_MILLIS,
    private val log: (String) -> Unit = {},
    // 首个 TCP 和协议就绪独立计时；未显式配置时保留原契约。
    private val protocolTimeoutMillis: Long? = null,
) {
    @Volatile var terminated = false
        private set
    private var firstStartNanos: Long? = null
    private var firstAcceptNanos: Long? = null
    private var success = false
    @Volatile var protocolTimedOut = false
        private set
    private var cancelTimer: (() -> Unit)? = null

    @Synchronized fun startSessionSent(sentAtNanos: Long) {
        if (terminated || firstStartNanos != null) return
        firstStartNanos = sentAtNanos
        log("StartSession atNs=$sentAtNanos")
        if (success || firstAcceptNanos != null) return
        val remaining = (timeoutMillis - (nowNanos() - sentAtNanos) / 1_000_000).coerceAtLeast(0)
        cancelTimer = schedule(remaining, ::expire)
    }

    @Synchronized fun accepted(event: AirPlayTcpAccepted): Boolean {
        if (terminated || event.listener !== listener || event.internal) return false
        if (firstAcceptNanos == null) {
            firstAcceptNanos = event.acceptedAtNanos
            log("first TCP atNs=${event.acceptedAtNanos}")
            // 只在首次外部接入启动协议计时，重复接入不能延长预算。
            cancel()
            if (!success) protocolTimeoutMillis?.let { cancelTimer = schedule(it, ::expireProtocol) }
        }
        return true
    }

    @Synchronized fun sessionEstablished(): Boolean {
        if (terminated) return false
        if (!success) log("session established atNs=${nowNanos()}")
        success = true
        cancel()
        return true
    }

    @Synchronized fun terminate(): Boolean {
        if (terminated) return false
        terminated = true
        cancel()
        return true
    }

    private fun expire() {
        synchronized(this) {
            if (terminated || success || firstAcceptNanos != null || firstStartNanos == null) return
            terminated = true
            cancelTimer = null
            log("first TCP timeout atNs=${nowNanos()} startNs=$firstStartNanos")
        }
        onTimeout()
    }

    private fun cancel() { cancelTimer?.invoke(); cancelTimer = null }

    private fun expireProtocol() {
        synchronized(this) {
            if (terminated || success || firstAcceptNanos == null) return
            terminated = true
            protocolTimedOut = true
            cancelTimer = null
            log("AirPlay protocol timeout atNs=${nowNanos()} firstTcpNs=$firstAcceptNanos")
        }
        onTimeout()
    }
}
