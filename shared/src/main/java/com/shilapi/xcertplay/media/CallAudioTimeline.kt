package com.shilapi.xcertplay.media

import java.util.concurrent.atomic.AtomicLong

/** 区分协议停止与资源真正释放；只观察通话音频组件，不把它解释为手机通话状态。 */
internal class CallAudioTimeline(
    private val report: (String) -> Unit,
    private val state: () -> String = { "" },
    private val nowMs: () -> Long = android.os.SystemClock::elapsedRealtime,
) {
    enum class Leg { DOWNLINK, UPLINK }
    class Ticket internal constructor(internal val serial: Long, internal val leg: Leg)
    private val run = runs.incrementAndGet()
    private val active = LinkedHashSet<Ticket>()
    private val stopping = HashSet<Ticket>()
    private var serial = 0L
    private var epoch = 0L
    private var endedAt: Long? = null
    private var closed = false

    @Synchronized fun start(leg: Leg): Ticket? {
        if (closed) return null
        if (active.isEmpty()) { epoch++; endedAt = null }
        return Ticket(++serial, leg).also { active.add(it); event("STARTED", it) }
    }

    @Synchronized fun stopRequested(ticket: Ticket?) {
        if (ticket != null && ticket in active && stopping.add(ticket)) event("STOP_REQUESTED", ticket)
    }

    @Synchronized fun released(ticket: Ticket?) {
        if (ticket == null || !active.remove(ticket)) return
        stopping.remove(ticket)
        if (!closed && active.isEmpty()) endedAt = nowMs()
        event("RELEASED", ticket)
    }

    @Synchronized fun snapshot(): String {
        val phase = when {
            closed -> "CLOSED"
            active.isNotEmpty() -> "ACTIVE"
            endedAt != null -> "AFTER_RELEASE"
            else -> "IDLE"
        }
        return "callRun=$run callEpoch=$epoch callPhase=$phase " +
            "callDownlinks=${active.count { it.leg == Leg.DOWNLINK }} " +
            "callUplinks=${active.count { it.leg == Leg.UPLINK }} " +
            "callStopping=${stopping.size} afterCallReleaseMs=${endedAt?.let { (nowMs() - it).coerceAtLeast(0) } ?: -1}"
    }

    @Synchronized fun close() {
        if (closed) return
        closed = true
        endedAt = null
        event("SESSION_CLOSED", null)
    }

    private fun event(stage: String, ticket: Ticket?) {
        // 查询和诊断失败不得影响录音、焦点或资源释放；没有定时器、PCM 或设备标识。
        runCatching {
            report("Audio: callLifecycle monoMs=${nowMs()} stage=$stage " +
                "callComponent=${ticket?.serial ?: 0} leg=${ticket?.leg ?: "none"} ${snapshot()} ${state()}")
        }
    }

    private companion object { val runs = AtomicLong() }
}
