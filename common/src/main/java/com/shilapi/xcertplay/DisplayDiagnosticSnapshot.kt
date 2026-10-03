package com.shilapi.xcertplay

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Small, redacted snapshot retained separately from the rotating session logs. */
internal object DisplayDiagnosticSnapshot {
    private const val PREFS = "diplay_display_diagnostics"
    private val fields = listOf("selection", "environment", "device", "connection", "request", "capability", "effective", "phone", "decoder", "decoderFailure", "output", "recovery", "presentation", "stats")
    private fun stamp() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

    @Synchronized
    fun selection(context: Context, previous: Int, selected: Int, reconnect: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("selection",
            "${stamp()} Size setting previous=$previous% selected=$selected% reconnect=$reconnect").apply()
    }

    @Synchronized
    fun begin(context: Context, request: String, capability: String, effective: String): String {
        val attempt = UUID.randomUUID().toString()
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        fields.filter { it != "selection" }.forEach(editor::remove)
        editor.putString("attempt", attempt)
        DiagnosticRedactor.redact(L7SystemDiagnostic.report(context))?.let {
            editor.putString("environment", "${stamp()} $it")
        }
        editor.putString("device", L7DeviceDiagnostic.capture(context).joinToString("\n") { "${stamp()} $it" })
        for ((key, line) in listOf("request" to request, "capability" to capability, "effective" to effective)) {
            DiagnosticRedactor.redact(line)?.let { editor.putString(key, "${stamp()} $it") }
        }
        editor.apply()
        return attempt
    }

    @Synchronized
    fun currentAttempt(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("attempt", null)

    @Synchronized
    fun record(context: Context, attempt: String?, message: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (attempt == null || prefs.getString("attempt", null) != attempt) return
        val field = when {
            message.startsWith("Phone display peer ") -> "phone"
            message.startsWith("Connection stage=") -> "connection"
            message.startsWith("Video: decoder=") || message.startsWith("Video: decoder configuration failed") -> "decoder"
            message.startsWith("Video: configure rejected ") || message.startsWith("Video: no usable decoder ") -> "decoderFailure"
            message.startsWith("Video: output format ") -> "output"
            message.startsWith("Video: recovery: ") -> "recovery"
            message == "Video: first frame rendered" -> "presentation"
            message.startsWith("Video: video stats") -> "stats"
            else -> return
        }
        val safe = DiagnosticRedactor.redact(message) ?: return
        prefs.edit().putString(field, "${stamp()} $safe").apply()
    }

    @Synchronized
    fun report(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return fields.flatMap { prefs.getString(it, null)?.lines().orEmpty().mapNotNull(DiagnosticRedactor::redact) }
            .joinToString("\n").ifEmpty { "No display negotiation recorded yet." }
    }
}
