package com.shilapi.xcertplay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.shilapi.xcertplay.diagnostics.*
import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong

/** 本页草稿与结果属于 Activity 控制器；窗口销毁停止样例，不自动续跑。 */
internal class GalaxyCodecProbeController(private val context: Context, private val occupied: () -> Boolean,
    private val catalog: (CodecProbeVideo) -> List<CodecProbeDecoder> = CodecProbeDecoder::list,
    private val create: (Long, CodecProbeMethod, CodecProbeVideo, String, Boolean, Surface,
        (CodecProbeStage) -> Unit, (CodecProbeResult) -> Unit) -> CodecProbeAttempt = { run, method, video, name, software, surface, stage, result ->
        GalaxyCodecProbeClient(context, run, method, video, name, software, surface, stage, result)
    }) : Closeable {
    var video = CodecProbeVideo.AVC
    var method = CodecProbeMethod.JAVA_NAME
    var allowSoftware = false
    var selected = ""
    var changed: (() -> Unit)? = null
    var stage = CodecProbeStage.BIND; private set
    var currentMethod: CodecProbeMethod? = null; private set
    var notice = ""; private set
    val results = mutableListOf<CodecProbeResult>()
    val lines = ArrayDeque<String>()
    var observed: Boolean? = null; private set
    private var client: CodecProbeAttempt? = null
    private var channel: DiagnosticChannel? = null
    private val pending = ArrayDeque<CodecProbeMethod>()
    private var target: Surface? = null
    private val handler = Handler(Looper.getMainLooper())
    private var closed = false
    private var cleanupUnconfirmed = false
    val busy get() = client != null || pending.isNotEmpty()
    val available get() = runCatching { catalog(video).filter { allowSoftware || it.hardware && !it.software } }.getOrDefault(emptyList())
    fun decoder(): CodecProbeDecoder? = available.let { it.firstOrNull { row -> row.name == selected } ?: it.firstOrNull() }
    fun start(surface: Surface, all: Boolean): Boolean {
        if (closed || cleanupUnconfirmed || busy || occupied() || !L7Agreement.canUse(context) || !surface.isValid || decoder() == null) return false
        notice = ""; observed = null; results.clear(); lines.clear()
        target = surface
        pending.addAll(if (all) CodecProbeMethod.entries else listOf(method))
        next()
        return true
    }
    private fun next() {
        if (closed || pending.isEmpty()) { changed?.invoke(); return }
        val surface = target
        val decoder = decoder()
        if (occupied() || surface?.isValid != true || decoder == null) { pending.clear(); notice = "INTERRUPTED"; changed?.invoke(); return }
        val selectedMethod = pending.removeFirst()
        val run = serial.incrementAndGet()
        currentMethod = selectedMethod; stage = CodecProbeStage.BIND
        channel = DiagnosticChannel(-run, GalaxyDiagnosticSink.create(context))
        channel?.emit(DiagnosticEvent.Component.VIDEO, DiagnosticEvent.Kind.START, DiagnosticEvent.State.REQUESTED,
            mapOf("methodOrdinal" to selectedMethod.ordinal.toLong(), "videoOrdinal" to video.ordinal.toLong()))
        record("run=$run method=$selectedMethod stage=BIND video=$video codec=${safe(decoder.name)} declaredHardware=${decoder.hardware} software=${decoder.software} ${GalaxyCodecProbeRecipe(selectedMethod).logSummary}")
        val next = create(run, selectedMethod, video, decoder.name, allowSoftware, surface, { phase ->
            if (client?.run == run) {
                stage = phase
                record("run=$run method=$selectedMethod stage=$phase")
                channel?.emit(DiagnosticEvent.Component.VIDEO, DiagnosticEvent.Kind.STATE, DiagnosticEvent.State.RUNNING,
                    mapOf("stageOrdinal" to phase.ordinal.toLong()))
                changed?.invoke()
            }
        }, { result -> complete(result) })
        client = next; next.start(); changed?.invoke()
    }
    private fun complete(result: CodecProbeResult) {
        if (client?.run != result.run) return
        client = null
        results += result
        while (results.size > CodecProbeMethod.entries.size) results.removeAt(0)
        observed = null
        stage = result.stage
        record("run=${result.run} method=${result.method} video=${result.video} stage=${result.stage} codec=${safe(result.name)} hardware=${result.hardware} software=${result.software} inputs=${result.inputs} outputs=${result.outputs} rendered=${result.rendered} eos=${result.eos} released=${result.released} code=${result.code} reason=${safe(result.reason)} elapsedMs=${result.elapsedMs} hardwarePassed=${result.hardwarePassed} workerStarted=${result.workerStarted} processExited=${result.processExited} configInputs=${result.configInputs} outputBytes=${result.outputBytes} surfaceOutput=${result.method.surfaceOutput}")
        channel?.emit(DiagnosticEvent.Component.VIDEO, if (result.decoded) DiagnosticEvent.Kind.RELEASE else DiagnosticEvent.Kind.FAILURE,
            if (result.decoded) DiagnosticEvent.State.ENDED else DiagnosticEvent.State.FAILED,
            mapOf("inputs" to result.inputs.toLong(), "outputs" to result.outputs.toLong(), "rendered" to result.rendered.toLong(),
                "released" to if (result.released) 1L else 0L, "hardware" to if (result.hardwarePassed) 1L else 0L, "code" to result.code.toLong()))
        channel?.emit(DiagnosticEvent.Component.VIDEO, DiagnosticEvent.Kind.RELEASE,
            if (result.released || result.processExited || !result.workerStarted) DiagnosticEvent.State.ENDED else DiagnosticEvent.State.UNKNOWN,
            mapOf("processExited" to if (result.processExited) 1L else 0L, "codecReleased" to if (result.released) 1L else 0L))
        channel?.close(); channel = null
        if (result.workerStarted && !result.released && !result.processExited) { pending.clear(); cleanupUnconfirmed = true; notice = "PROCESS_UNCONFIRMED" }
        changed?.invoke()
        if (!closed && pending.isNotEmpty()) handler.postDelayed({ next() }, 200)
    }
    fun observe(visible: Boolean) {
        val result = results.lastOrNull() ?: return
        if (busy || closed || result.outputs <= 0 || !result.method.surfaceOutput) return
        observed = visible
        record("run=${result.run} method=${result.method} observation=${if (visible) "VISIBLE_MOTION" else "MISSING_OR_ABNORMAL"} origin=USER")
        changed?.invoke()
    }
    fun stop(reason: String = "CANCELLED") {
        val wasBusy = busy
        pending.clear(); target = null
        if (client != null) channel?.emit(DiagnosticEvent.Component.VIDEO, DiagnosticEvent.Kind.STOP, DiagnosticEvent.State.REQUESTED)
        client?.stop(reason)
        if (wasBusy) notice = reason
        changed?.invoke()
    }
    private fun record(value: String) {
        val line = "CODEC_PROBE $value"
        lines.addLast(line); while (lines.size > 32) lines.removeFirst()
        runCatching { L7DebugLog.record(line) }
    }
    override fun close() { closed = true; changed = null; stop(); channel?.close() }
    companion object {
        private val serial = AtomicLong(System.currentTimeMillis())
        private fun safe(value: String) = value.take(96).replace(Regex("[^a-zA-Z0-9._-]"), "_").ifEmpty { "none" }
    }
}
