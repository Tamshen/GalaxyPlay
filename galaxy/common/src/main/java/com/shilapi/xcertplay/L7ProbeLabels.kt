package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.host.R
import java.text.DateFormat
import java.util.Date

/** 报告只保存稳定标识，界面标题和结果说明随应用语言切换。 */
internal class L7ProbeLabels(private val context: Context) {
    fun text(id: Int) = context.getString(id)
    fun time(value: Long) = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM,
        context.resources.configuration.locales[0]).format(Date(value))
    fun name(item: L7ProbeItem): String = environmentNames[item.id]?.let(::text) ?: item.name.substringAfterLast('.')
    fun reason(item: L7ProbeItem): String {
        if (item.reason == "REPORTING_UNVERIFIED") return context.getString(R.string.l7_probe_reporting_details,
            item.facts["permissionCount"].orEmpty(), item.facts["declaredCount"].orEmpty(),
            item.facts["grantedCount"].orEmpty(), item.facts["definitionVisibleCount"].orEmpty(),
            item.facts["restrictedCount"].orEmpty(), item.facts["sdkVisibleCount"].orEmpty(), item.facts["sdkCount"].orEmpty()) +
                if (item.id == "REPORT-QNX") "\n${text(R.string.l7_probe_qnx_unconfirmed)}" else ""
        if (L7ProbeStatus.of(item) == L7ProbeStatus.UNSUPPORTED) return text(R.string.l7_probe_reason_unsupported)
        if (item.id in setOf("ENV-OVERLAY", "ENV-WRITE-SETTINGS") && item.facts["allowed"] == "false")
            return text(R.string.l7_probe_reason_access)
        return text(when (item.reason) {
        "HOTSPOT_STATE_READ" -> R.string.l7_probe_hotspot_state_read
        "VALID_CONFIG" -> R.string.l7_probe_valid_config
        "EMPTY_CONFIG" -> R.string.l7_probe_empty_config
        "MASKED_PASSWORD" -> R.string.l7_probe_masked_password
        "INVALID_CONFIG" -> R.string.l7_probe_invalid_config
        "HOTSPOT_DENIED" -> R.string.l7_probe_hotspot_denied
        "SERVICE_UNAVAILABLE" -> R.string.l7_probe_service_unavailable
        "INTERFACE_NOT_VISIBLE" -> R.string.l7_probe_interface_not_visible
        "INVALID_RESPONSE" -> R.string.l7_probe_invalid_response
        "SDK_CONTRACT_CHECKED" -> R.string.l7_probe_sdk_contract_checked
        "QUERY_ONLY" -> R.string.l7_probe_query_only
        "DEFINITION_NOT_VISIBLE" -> R.string.l7_probe_definition_not_visible
        "NOT_DECLARED" -> R.string.l7_probe_not_declared
        "NOT_GRANTED" -> R.string.l7_probe_not_granted
        "APP_OP_RESTRICTED" -> R.string.l7_probe_app_op_restricted
        "GRANTED_NOT_CALLED" -> R.string.l7_probe_granted_not_called
        "SPECIAL_ACCESS_ALLOWED" -> R.string.l7_probe_special_allowed
        "SPECIAL_ACCESS_DENIED" -> R.string.l7_probe_reason_access
        "SPECIAL_ACCESS_UNKNOWN" -> R.string.l7_probe_special_unknown
        "API_NOT_APPLICABLE" -> R.string.l7_probe_api_not_applicable
        "NOT_RUN" -> R.string.l7_probe_not_run
        else -> R.string.l7_probe_query_failed
        })
    }
    fun compactReason(item: L7ProbeItem): String {
        if (item.domain == "HOTSPOT") return reason(item)
        if (item.reason == "REPORTING_UNVERIFIED") return context.getString(R.string.l7_probe_reporting_short,
            item.facts["grantedCount"].orEmpty(), item.facts["permissionCount"].orEmpty(),
            item.facts["sdkVisibleCount"].orEmpty(), item.facts["sdkCount"].orEmpty())
        return text(when {
        L7ProbeStatus.of(item) == L7ProbeStatus.UNSUPPORTED -> R.string.l7_probe_reason_unsupported
        item.id in setOf("ENV-OVERLAY", "ENV-WRITE-SETTINGS") && item.facts["allowed"] == "false" -> R.string.l7_probe_reason_access
        item.reason == "SDK_CONTRACT_CHECKED" -> R.string.l7_probe_sdk_contract_checked
        item.reason == "QUERY_ONLY" -> R.string.l7_probe_reason_query
        item.reason == "GRANTED_NOT_CALLED" -> R.string.l7_probe_reason_granted
        item.reason == "SPECIAL_ACCESS_ALLOWED" -> R.string.l7_probe_reason_granted
        item.reason == "SPECIAL_ACCESS_DENIED" -> R.string.l7_probe_reason_access
        item.reason == "SPECIAL_ACCESS_UNKNOWN" -> R.string.l7_probe_special_unknown
        item.reason == "API_NOT_APPLICABLE" -> R.string.l7_probe_api_not_applicable
        item.reason == "NOT_DECLARED" -> R.string.l7_probe_reason_declared
        item.reason == "NOT_GRANTED" -> R.string.l7_probe_reason_grant
        item.reason == "APP_OP_RESTRICTED" -> R.string.l7_probe_reason_appop
        item.reason == "DEFINITION_NOT_VISIBLE" -> R.string.l7_probe_reason_unknown
        item.reason == "NOT_RUN" -> R.string.l7_probe_not_run
        else -> R.string.l7_probe_reason_error
        })
    }
    fun phase(report: L7ProbeReport) = text(when (report.phase) {
        L7ProbePhase.RUNNING -> R.string.l7_probe_running
        L7ProbePhase.COMPLETED -> R.string.l7_probe_completed
        L7ProbePhase.CANCELLED -> R.string.l7_probe_cancelled
        L7ProbePhase.TIMED_OUT -> R.string.l7_probe_timed_out
        L7ProbePhase.INTERRUPTED -> R.string.l7_probe_interrupted
    })
    fun summary(report: L7ProbeReport): String {
        val statuses = report.items.map(L7ProbeStatus::of)
        return context.getString(R.string.l7_probe_table_summary, statuses.size,
            statuses.count { it.matches(1) }, statuses.count { it.matches(2) },
            statuses.count { it.matches(3) }, statuses.count { it.matches(4) },
            statuses.count { it.matches(5) || it.matches(6) })
    }
    fun readable(report: L7ProbeReport) = buildString {
        appendLine("${text(R.string.app_name)} ${report.version} · ${text(R.string.l7_probe_title)}")
        appendLine("${report.id} · ${time(report.started)} · ${phase(report)}")
        appendLine(summary(report))
        report.items.forEach { appendLine("${name(it)}: ${reason(it)}") }
    }

    private val environmentNames = mapOf(
        "HOTSPOT-STATE" to R.string.l7_probe_hotspot_state, "HOTSPOT-CONFIG" to R.string.l7_probe_hotspot_config,
        "HOTSPOT-LEGACY" to R.string.l7_probe_hotspot_legacy,
        "REPORT-MEDIA" to R.string.l7_probe_reporting_media, "REPORT-HUD" to R.string.l7_probe_reporting_hud,
        "REPORT-QNX" to R.string.l7_probe_reporting_qnx,
        "ENV-SYSTEM" to R.string.l7_probe_system, "ENV-APP" to R.string.l7_probe_app,
        "ENV-APK" to R.string.l7_probe_apk, "ENV-WINDOW" to R.string.l7_probe_window,
        "ENV-SDK-CONTRACT" to R.string.l7_probe_sdk_contract, "ENV-MEDIA-SESSIONS" to R.string.l7_probe_media_sessions,
        "ENV-LIBRARIES" to R.string.l7_probe_libraries, "ENV-AUDIO" to R.string.l7_probe_audio,
        "ENV-PACKAGES" to R.string.l7_probe_packages, "ENV-EXECUTOR" to R.string.l7_probe_executor,
        "ENV-NETWORK" to R.string.l7_probe_network, "ENV-USB" to R.string.l7_probe_usb,
        "ENV-RUNTIME" to R.string.l7_probe_runtime,
        "ENV-OVERLAY" to R.string.l7_probe_overlay, "ENV-WRITE-SETTINGS" to R.string.l7_probe_write_settings)
}
