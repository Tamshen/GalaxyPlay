package com.shilapi.xcertplay

import android.os.SystemClock
import java.util.ArrayDeque

/** 仅保存按键阶段与布尔状态，限制内存；不保存手机身份、歌曲或协议载荷。 */
internal class L7SteeringTraceStore(
    private val clock: () -> Long,
    private val log: (String) -> Unit,
    private val capacity: Int = 256,
    private val model: () -> String = { "unknown" },
    private val logger: () -> ((String) -> Unit) = { log },
) {
    data class Event(val id: Long, val generation: Long, val source: String, val command: Int,
                     val stage: String, val detail: String, val elapsedMs: Long, val atMs: Long)
    data class Snapshot(val revision: Long, val connected: Boolean, val generation: Long,
                        val events: List<Event>, val states: Map<String, String>)
    private val events = ArrayDeque<Event>()
    private val states = linkedMapOf<String, String>()
    private var serial = 0L
    private var revision = 0L
    private var generation = 0L
    private var connected = false
    private val run = clock()

    @Synchronized fun begin(source: String, command: Int, detail: String = ""): L7SteeringTrace {
        val trace = L7SteeringTrace(++serial, generation, safe(source), command, clock(), this, safe(model()), logger())
        trace.step("INPUT", detail)
        return trace
    }

    @Synchronized fun connection(active: Boolean) {
        if (active == connected) return
        connected = active
        generation++
        states.clear()
        begin("session", -1).step(if (active) "CONNECTED" else "DISCONNECTED")
    }

    @Synchronized fun state(name: String, value: String) {
        if (states[name] == value) return
        states[name] = safe(value)
        begin(name, -1).step("STATE", value)
    }

    @Synchronized internal fun append(trace: L7SteeringTrace, stage: String, detail: String) {
        val now = clock()
        val event = Event(trace.id, trace.generation, trace.source, trace.command,
            safe(stage), safe(detail), (now - trace.started).coerceAtLeast(0), now)
        events.addLast(event)
        while (events.size > capacity) events.removeFirst()
        revision++
        trace.log("STEERING_TRACE run=$run trace=${event.id} generation=${event.generation} model=${trace.model} source=${event.source} index=${event.command} stage=${event.stage} elapsedMs=${event.elapsedMs} monoMs=$now detail=${event.detail} thread=${safe(Thread.currentThread().name)}")
    }

    @Synchronized fun snapshot() = Snapshot(revision, connected, generation, events.toList(), states.toMap())
    @Synchronized fun clear() { events.clear(); revision++ }
    private fun safe(value: String) = value.take(160).replace(Regex("[^A-Za-z0-9_:=., /+-]"), "_")
}

internal class L7SteeringTrace internal constructor(
    val id: Long, val generation: Long, val source: String, val command: Int,
    internal val started: Long, private val store: L7SteeringTraceStore,
    val model: String,
    internal val log: (String) -> Unit,
) {
    fun step(stage: String, detail: String = "") = store.append(this, stage, detail)
}

internal object L7SteeringDiagnostics {
    @Volatile private var target: SessionLogFile? = null
    @Volatile private var app: android.content.Context? = null
    @Volatile private var listeningConfiguration: GalaxyConfigurationEvidence? = null
    @Volatile private var listeningModel: String? = null
    val store = L7SteeringTraceStore(SystemClock::elapsedRealtime, ::record,
        model = { listeningModel ?: CarPlayBackgroundSession.configuration?.let {
            it.model.takeIf { value -> value in setOf("l7", "l6", "custom") } ?: "l7"
        } ?: app?.let { L7AudioTemplates.model(it).id } ?: "unknown" },
        logger = {
            val context = app
            val effective = CarPlayBackgroundSession.configuration
            val snapshot = listeningConfiguration ?: if (context != null && effective != null)
                runCatching { GalaxyConfigurationEvidence.from(context, effective) }.getOrNull()
            else context?.let { runCatching { GalaxyConfigurationEvidence.capture(it) }.getOrNull() }
            val write: (String) -> Unit = { line -> L7DebugLog.record(line, target, snapshot) }
            write
        })

    @Synchronized fun initialize(context: android.content.Context) {
        app = context.applicationContext
        VehicleSteeringInputLog.initialize(context)
        if (target == null) target = SessionLogFile(java.io.File(context.filesDir, "logs/steering.log"),
            listOf("steering-previous.log", "steering-previous-2.log"))
    }

    internal fun record(line: String) {
        L7DebugLog.record(line, target, listeningConfiguration)
    }
    fun freezeListening(context: android.content.Context) {
        initialize(context)
        listeningConfiguration = runCatching { GalaxyConfigurationEvidence.capture(context) }.getOrNull()
        listeningModel = L7AudioTemplates.model(context).id
    }
    fun endListening() { listeningConfiguration = null; listeningModel = null }
    fun begin(source: String, index: Int, detail: String = ""): L7SteeringTrace =
        store.begin(source, index, detail).also { SteeringListening.input(it, detail) }
}
