package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.host.R
import java.text.DateFormat
import java.util.Date

/** 报告只保存稳定标识，界面标题和结果说明随应用语言切换。 */
internal class L7ProbeLabels(private val context: Context) {
    fun text(id: Int) = context.getString(id)
    fun time(value: Long) = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(value))
    fun name(item: L7ProbeItem): String = environmentNames[item.id]?.let(::text) ?: item.name.substringAfterLast('.')
    fun reason(item: L7ProbeItem): String = text(when (item.reason) {
        "QUERY_ONLY" -> R.string.l7_probe_query_only
        "DEFINITION_NOT_VISIBLE" -> R.string.l7_probe_definition_not_visible
        "NOT_DECLARED" -> R.string.l7_probe_not_declared
        "NOT_GRANTED" -> R.string.l7_probe_not_granted
        "APP_OP_RESTRICTED" -> R.string.l7_probe_app_op_restricted
        "GRANTED_NOT_CALLED" -> R.string.l7_probe_granted_not_called
        "NOT_RUN" -> R.string.l7_probe_not_run
        else -> R.string.l7_probe_query_failed
    })
    fun phase(report: L7ProbeReport) = text(when (report.phase) {
        L7ProbePhase.RUNNING -> R.string.l7_probe_running
        L7ProbePhase.COMPLETED -> R.string.l7_probe_completed
        L7ProbePhase.CANCELLED -> R.string.l7_probe_cancelled
        L7ProbePhase.TIMED_OUT -> R.string.l7_probe_timed_out
        L7ProbePhase.INTERRUPTED -> R.string.l7_probe_interrupted
    })
    fun summary(report: L7ProbeReport): String {
        val counts = report.counts()
        val notRun = report.items.count { it.reason == "NOT_RUN" }
        val verified = counts.getValue(L7ProbeOutcome.VERIFIED)
        val restricted = counts.getValue(L7ProbeOutcome.DENIED)
        return context.getString(R.string.l7_probe_summary, verified, restricted,
            report.items.size - verified - restricted - notRun, notRun)
    }
    fun readable(report: L7ProbeReport) = buildString {
        appendLine("L7 CarPlay ${report.version} · ${text(R.string.l7_probe_title)}")
        appendLine("${report.id} · ${time(report.started)} · ${phase(report)}")
        appendLine(summary(report))
        report.items.forEach { appendLine("${name(it)}: ${reason(it)}") }
    }

    private val environmentNames = mapOf(
        "ENV-SYSTEM" to R.string.l7_probe_system, "ENV-APP" to R.string.l7_probe_app,
        "ENV-APK" to R.string.l7_probe_apk, "ENV-WINDOW" to R.string.l7_probe_window,
        "ENV-LIBRARIES" to R.string.l7_probe_libraries, "ENV-AUDIO" to R.string.l7_probe_audio,
        "ENV-PACKAGES" to R.string.l7_probe_packages, "ENV-EXECUTOR" to R.string.l7_probe_executor,
        "ENV-NETWORK" to R.string.l7_probe_network, "ENV-USB" to R.string.l7_probe_usb,
        "ENV-OVERLAY" to R.string.l7_probe_overlay, "ENV-WRITE-SETTINGS" to R.string.l7_probe_write_settings)
}
