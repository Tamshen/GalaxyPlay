package com.shilapi.xcertplay

/** 将一次监听输入与事后标注绑定；状态锁内不写日志，避免输入线程与界面互相等待。 */
internal class SteeringListeningController(private val clock: () -> Long, private val log: (String) -> Unit,
    private val ended: () -> Unit = {}) {
    enum class Phase { IDLE, LISTENING, LABEL, STOPPED }
    data class Sample(val id: Long, val traces: List<L7SteeringTrace>, val label: String? = null)
    data class Snapshot(val run: Long, val phase: Phase, val samples: List<Sample>, val pending: Sample?, val revision: Long)
    private var run = 0L
    private var sequence = 0L
    private var revision = 0L
    private var phase = Phase.IDLE
    private var started = 0L
    private var model = ""
    private var lastInput = 0L
    private var collecting = mutableListOf<L7SteeringTrace>()
    private var pending: Sample? = null
    private var samples = emptyList<Sample>()

    @Synchronized fun snapshot() = Snapshot(run, phase, samples.toList(), pending, revision)
    @Synchronized fun active() = phase == Phase.LISTENING || phase == Phase.LABEL

    fun start(selectedModel: String) {
        val startedRun = synchronized(this) {
            run++; revision++; model = selectedModel; started = clock(); phase = Phase.LISTENING
            collecting.clear(); pending = null; samples = emptyList()
            run
        }
        log("SteeringListen stage=START run=$startedRun model=$selectedModel")
    }

    @Synchronized fun input(trace: L7SteeringTrace, detail: String) {
        if (phase != Phase.LISTENING || trace.model != model || trace.started < started || !candidate(trace, detail)) return
        if (clock() - started >= 120_000) return
        if (collecting.isEmpty() && ("action=1" in detail || Regex("repeat=[1-9]").containsMatchIn(detail))) return
        if (collecting.none { it.id == trace.id } && collecting.size < 24) collecting.add(trace)
        lastInput = clock()
        revision++
    }

    fun poll(selectedModel: String) {
        val reason = synchronized(this) {
            if (!active()) return
            when {
                selectedModel != model -> "MODEL_CHANGED"
                clock() - started >= 120_000 -> "TIME_LIMIT"
                else -> {
                    if (phase == Phase.LISTENING && collecting.isNotEmpty() &&
                        (clock() - lastInput >= 350 || clock() - collecting.first().started >= 1500)) {
                        pending = Sample(++sequence, collecting.toList())
                        collecting.clear(); phase = Phase.LABEL; revision++
                    }
                    null
                }
            }
        }
        if (reason != null) stop(reason)
    }

    fun answer(id: Long, label: String): Boolean {
        require(label in LABELS)
        val (sample, labeledRun) = synchronized(this) {
            val value = pending ?: return false
            if (phase != Phase.LABEL || value.id != id || clock() - started >= 120_000) return false
            samples = (samples + value.copy(label = label)).takeLast(12)
            pending = null; phase = Phase.LISTENING; revision++
            value to run
        }
        sample.traces.forEach { it.step(if (label == "SKIP") "USER_SKIP" else "USER_LABEL",
            "listenRun=$labeledRun sample=${sample.id} key=$label origin=MANUAL") }
        return true
    }

    fun stop(reason: String) {
        val currentRun = synchronized(this) {
            if (!active()) return
            phase = Phase.STOPPED; pending = null; collecting.clear(); revision++
            run
        }
        log("SteeringListen stage=STOP run=$currentRun reason=$reason")
        ended()
    }

    private fun candidate(trace: L7SteeringTrace, detail: String): Boolean {
        val source = trace.source
        val input = source == "mediacenter" || source == "oem-custom-action" || source == "vehicle-broadcast" ||
            source.startsWith("vehicle-") && source.endsWith("-key") || source.startsWith("window-key:") ||
            source.startsWith("media-session-key:") || source.startsWith("voice-") ||
            source in setOf("controller-play", "controller-pause", "next", "previous")
        if (!input) return false
        val code = Regex("(?:^| )code=(\\d+)").find(detail)?.groupValues?.get(1)?.toIntOrNull()
        return code !in setOf(3, 4, 61, 66, 82, 111)
    }

    companion object {
        val LABELS = listOf("LEFT", "RIGHT", "PLAY_PAUSE", "VOICE_SHORT", "VOICE_LONG", "VOLUME_UP", "VOLUME_DOWN", "OTHER", "SKIP")
    }
}

internal object SteeringListening {
    val controller = SteeringListeningController(android.os.SystemClock::elapsedRealtime,
        L7SteeringDiagnostics::record, L7SteeringDiagnostics::endListening)
    fun active() = controller.active()
    fun input(trace: L7SteeringTrace, detail: String) = controller.input(trace, detail)
    fun stop(reason: String) = controller.stop(reason)
}
