package com.shilapi.xcertplay

import java.io.Closeable

/** 一次点击串行执行；人工判断绑定本次证据，重试必须先完成上一项释放。 */
internal class GalaxyDebugFlow(
    val checks: List<Check>, private val now: () -> Long,
    private val log: (String) -> Unit,
) : Closeable {
    data class Check(val id: String, val title: Int, val body: Int, val start: () -> Attempt)
    interface Attempt {
        fun poll(): Evidence
        fun observe(normal: Boolean): Boolean = true
        fun stop()
        fun retry() = stop()
        fun released(): Boolean
    }
    enum class Phase { IDLE, RUNNING, QUESTION, RELEASING, FINISHED, STOPPED }
    enum class Answer { CONFIRM, MISSING, RETRY }
    data class Evidence(val automatic: Boolean = false, val question: Boolean = false,
        val canConfirm: Boolean = false, val unavailable: Boolean = false,
        val token: String = "", val detail: String = "")
    var phase = Phase.IDLE; private set
    var index = 0; private set
    var generation = 0L; private set
    var evidence = Evidence(); private set
    var completed = 0; private set
    var missing = 0; private set
    var unsupported = 0; private set
    private var current: Attempt? = null
    private var releaseStarted = 0L
    private var retry = false
    private var stopped = false
    private var run = 0L
    private val check get() = checks.getOrNull(index)
    val canStart get() = current == null && phase in setOf(Phase.IDLE, Phase.FINISHED, Phase.STOPPED)

    fun start(): Boolean {
        if (!canStart) return false
        index = 0; completed = 0; missing = 0; unsupported = 0; stopped = false; run = now()
        event("START"); begin(); return true
    }
    private fun begin() {
        val check = check ?: run { phase = Phase.FINISHED; event("FINISHED"); return }
        generation++; evidence = Evidence(); phase = Phase.RUNNING
        event("CHECK_START")
        current = try { check.start() } catch (error: Exception) {
            event("START_FAILED", "error=${error.javaClass.simpleName}")
            object : Attempt {
                override fun poll() = Evidence(question = true, unavailable = true)
                override fun stop() {}
                override fun released() = true
            }
        }
    }
    fun poll() {
        if (phase == Phase.RELEASING) {
            if (current?.released() == true) {
                current = null
                if (stopped) { phase = Phase.STOPPED; event("STOPPED") }
                else { if (!retry) index++; begin() }
            } else if (now() - releaseStarted > 5_000) {
                stopped = true; phase = Phase.STOPPED; event("RELEASE_UNCONFIRMED")
            }
            return
        }
        if (phase !in setOf(Phase.RUNNING, Phase.QUESTION)) return
        evidence = current?.poll() ?: return
        if (evidence.automatic) {
            if (evidence.unavailable) unsupported++ else completed++
            event(if (evidence.unavailable) "UNAVAILABLE" else "AUTOMATIC_COMPLETED"); release(false)
        } else phase = if (evidence.question) Phase.QUESTION else Phase.RUNNING
    }
    fun answer(expectedGeneration: Long, expectedToken: String, answer: Answer): Boolean {
        if (phase != Phase.QUESTION || generation != expectedGeneration || stopped) return false
        val fresh = current?.poll() ?: return false
        evidence = fresh
        if (!fresh.question || fresh.token != expectedToken || answer == Answer.CONFIRM && !fresh.canConfirm) return false
        if (answer == Answer.RETRY) { event("USER_RETRY"); release(true); return true }
        if (!current!!.observe(answer == Answer.CONFIRM)) return false
        if (fresh.unavailable) unsupported++ else if (answer == Answer.MISSING) missing++ else completed++
        event(if (fresh.unavailable) "UNAVAILABLE" else if (answer == Answer.CONFIRM) "USER_CONFIRMED" else "USER_MISSING")
        release(false); return true
    }
    private fun release(again: Boolean) {
        retry = again; releaseStarted = now(); phase = Phase.RELEASING
        if (again) current?.retry() else current?.stop()
    }
    fun stop(reason: String = "USER") {
        if (phase in setOf(Phase.IDLE, Phase.FINISHED, Phase.STOPPED)) return
        stopped = true; event("STOP_REQUESTED", "reason=$reason"); release(false)
    }
    private fun event(stage: String, extra: String = "") {
        runCatching { log("DEBUG_SUITE run=$run generation=$generation check=${check?.id ?: "END"} " +
            "stage=$stage completed=$completed missing=$missing unavailable=$unsupported $extra") }
    }
    override fun close() { stop("PAGE_CLOSED") }
}
