package com.shilapi.xcertplay.diagnostics

/** 固定高频阶段按窗口汇总；关键调用仍留前后锚点，失败和慢调用立即留证。仅由所属 worker 使用。 */
class SampledDiagnosticOperation(
    private val report: (String) -> Unit,
    frequentStages: Set<String>,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private class Window {
        var lastAnchor = Long.MIN_VALUE
        var calls = 0L
        var empty = 0L
        var maxMillis = 0L
    }
    private val windows = frequentStages.associateWith { Window() }
    private var serial = 0L

    fun <T> run(stage: String, action: () -> T): T {
        val window = windows[stage]
        if (window == null) require(STAGE.matches(stage))
        val operation = ++serial
        val start = nanoTime()
        val sampled = window == null || window.lastAnchor == Long.MIN_VALUE || start - window.lastAnchor >= WINDOW_NS
        if (sampled) {
            if (window != null) { flush(stage, window); window.lastAnchor = start }
            emit("stage=$stage operation=$operation phase=BEFORE" + if (window != null) " sampled=true" else "")
        }
        if (window != null) window.calls++
        try {
            val value = action()
            val duration = elapsed(start)
            if (window != null) {
                window.maxMillis = maxOf(window.maxMillis, duration)
                if (value == null) window.empty++
            }
            val rejected = value is Boolean && !value
            if (sampled || rejected || duration >= SLOW_MS) {
                val outcome = when {
                    rejected -> "REJECTED"
                    value == null -> "EMPTY"
                    value is Boolean -> "ACCEPTED"
                    else -> "RETURNED"
                }
                emit("stage=$stage operation=$operation phase=AFTER outcome=$outcome durationMs=$duration sampled=$sampled")
            }
            return value
        } catch (error: Throwable) {
            val duration = elapsed(start)
            if (window != null) window.maxMillis = maxOf(window.maxMillis, duration)
            emit("stage=$stage operation=$operation phase=AFTER outcome=FAILED durationMs=$duration exceptionType=${error.javaClass.simpleName}")
            throw error
        }
    }

    fun flush() = windows.forEach { (stage, window) -> flush(stage, window) }
    private fun flush(stage: String, window: Window) {
        if (window.calls == 0L) return
        emit("stage=$stage phase=SUMMARY calls=${window.calls} empty=${window.empty} maxDurationMs=${window.maxMillis}")
        window.calls = 0; window.empty = 0; window.maxMillis = 0
    }
    private fun elapsed(start: Long) = ((nanoTime() - start) / 1_000_000).coerceAtLeast(0)
    private fun emit(value: String) { runCatching { report(value) } }
    private companion object {
        const val WINDOW_NS = 5_000_000_000L
        const val SLOW_MS = 250L
        val STAGE = Regex("[A-Za-z][A-Za-z0-9_]{0,31}")
    }
}
