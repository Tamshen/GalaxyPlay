package com.shilapi.xcertplay

import android.content.Context
import android.os.SystemClock
import java.io.File

/** 语音测试仅保留技术计数；不接收 PCM、编码包、端点或语音文字。 */
internal class L7VoiceTraceStore(private val log: (String) -> Unit = {}) {
    data class Snapshot(val revision: Long, val lines: List<String>, val microphone: Map<String, String>)
    private var owner: Any? = null
    private var generation = 0
    private var revision = 0L
    private val lines = ArrayDeque<String>()
    private var microphone = emptyMap<String, String>()

    @Synchronized fun session(generation: Int = 0): Any = Any().also {
        owner = it; this.generation = generation; microphone = emptyMap(); revision++
    }

    @Synchronized fun observe(expected: Any, message: String) {
        if (expected !== owner || !(message.startsWith("Microphone:") || message.startsWith("Audio: microphone "))) return
        val fields = FIELD.findAll(message).map { it.groupValues[1] to it.groupValues[2] }
            .filter { it.first in ALLOWED }.toMap()
        if (fields["type"] !in setOf("speechrecognition", "telephony", "other")) return
        val phase = when {
            message.startsWith("Microphone: failure") -> "FAILURE"
            fields["ended"] == "true" -> "ENDED"
            message.startsWith("Microphone: start") -> "START"
            else -> "STATS"
        }
        microphone = if (phase != "START" && microphone["type"] == fields["type"]) microphone + fields + ("phase" to phase)
            else fields + ("phase" to phase)
        append("VOICE_UPLINK generation=$generation monoMs=${SystemClock.elapsedRealtime()} phase=$phase " +
            fields.entries.joinToString(" ") { "${it.key}=${it.value}" })
    }

    @Synchronized fun record(message: String) {
        require(message.startsWith("VOICE_TEST ") || message.startsWith("VOICE_SIRI "))
        append(message.take(512).replace(Regex("[^A-Za-z0-9_=., /:+-]"), "_"))
    }

    private fun append(line: String) {
        lines.addLast(line)
        while (lines.size > 32) lines.removeFirst()
        revision++
        runCatching { log(line) }
    }
    @Synchronized fun snapshot() = Snapshot(revision, lines.toList(), microphone.toMap())
    @Synchronized fun clear() { lines.clear(); revision++ }

    private companion object {
        val FIELD = Regex("([A-Za-z]+)=([A-Za-z0-9_.+-]+)")
        val ALLOWED = setOf("type", "source", "codec", "rate", "channels", "frameMs", "routedDeviceType",
            "captureBytes", "reads", "zeroReads", "readErrors", "readMaxMs", "encodedFrames", "emptyEncodedFrames",
            "udpSent", "sendErrors", "sendGapMaxMs", "ended", "stage", "error", "code", "recordingState",
            "bytes", "samples", "zeroPercent", "rms", "peak", "packetsSent")
    }
}

internal object L7VoiceDiagnostics {
    @Volatile private var target: SessionLogFile? = null
    val store = L7VoiceTraceStore { line -> L7DebugLog.record(line, target) }
    @Synchronized fun initialize(context: Context) {
        if (target == null) target = SessionLogFile(File(context.filesDir, "logs/voice-input.log"),
            listOf("voice-input-previous.log", "voice-input-previous-2.log"))
    }
    fun logger(context: Context): (String) -> Unit {
        initialize(context)
        return L7DebugLog.logger(context, target)
    }
    fun freezeConnection(context: Context) {
        target?.configuration = runCatching { GalaxyConfigurationEvidence.capture(context) }.getOrNull()
    }
}
