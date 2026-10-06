package com.shilapi.xcertplay.diagnostics

import java.util.concurrent.atomic.AtomicLong

/** 阻塞调用前即留下锚点；只记录阶段、结果与耗时，诊断异常不影响原调用。 */
class DiagnosticOperation(
    private val report: (String) -> Unit,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val sequence = AtomicLong()
    fun <T> run(stage: String, action: () -> T): T {
        require(stage.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,31}")))
        val operation = sequence.incrementAndGet()
        val started = nanoTime()
        emit("stage=$stage operation=$operation phase=BEFORE")
        try {
            val result = action()
            val outcome = when (result) {
                is Boolean -> if (result) "ACCEPTED" else "REJECTED"
                null -> "EMPTY"
                else -> "RETURNED"
            }
            emit("stage=$stage operation=$operation phase=AFTER outcome=$outcome durationMs=${elapsed(started)}")
            return result
        } catch (error: Throwable) {
            emit("stage=$stage operation=$operation phase=AFTER outcome=FAILED durationMs=${elapsed(started)} exceptionType=${error.javaClass.simpleName}")
            throw error
        }
    }
    private fun elapsed(started: Long) = ((nanoTime() - started) / 1_000_000).coerceAtLeast(0)
    private fun emit(value: String) { runCatching { report(value) } }
}
