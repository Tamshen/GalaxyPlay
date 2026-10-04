package com.shilapi.xcertplay

import android.content.Context
import android.provider.Settings
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R

/** 诊断页沿用设置条目；导出与服务状态由宿主刷新，不在组件内启动轮询。 */
internal class L7DiagnosticSettings(
    private val context: Context,
    parent: LinearLayout,
    onLogs: () -> Unit,
    onExport: () -> Unit,
    onChooseLocation: () -> Unit,
    onPermission: (Boolean) -> Unit,
) {
    private var exportFailed = false
    private val export = L7Components.actionRow(context, context.getString(R.string.save_diagnostic_report),
        context.getString(R.string.reports_save_to_downloads_diplay), click = onExport)
    private val state = L7SettingRow(context, context.getString(R.string.l7_debug_state_title), "")
    private val start = L7Components.actionRow(context, context.getString(R.string.l7_debug_start)) { onPermission(true) }
    private val stop = L7Components.actionRow(context, context.getString(R.string.l7_debug_stop)) { L7DebugOverlayService.stop(context) }
    private val remote: L7RemoteLogSettings

    init {
        L7SettingsSection.add(parent, context.getString(R.string.l7_logs_and_reports),
            footer = context.getString(R.string.nothing_is_sent_automatically_protocol_payloads_and_creden).trim()) { card ->
            card.addView(L7Components.actionRow(context, context.getString(R.string.l7_debug_view),
                context.getString(R.string.l7_debug_view_hint), click = onLogs))
            card.addView(export)
            card.addView(L7Components.actionRow(context, context.getString(R.string.choose_save_location),
                context.getString(R.string.l7_report_location_hint), click = onChooseLocation))
        }
        L7SettingsSection.add(parent, context.getString(R.string.l7_debug_title),
            footer = context.getString(R.string.l7_debug_desc)) { card ->
            card.addView(state)
            card.addView(L7Components.actionRow(context, context.getString(R.string.l7_debug_permission),
                context.getString(R.string.l7_debug_permission_hint)) { onPermission(false) })
            card.addView(start)
            card.addView(stop)
        }
        remote = L7RemoteLogSettings(context, parent)
    }

    fun update(exporting: Boolean) {
        remote.update()
        if (exporting) exportFailed = false
        export.setFeedback(when {
            exporting -> context.getString(R.string.saving_report)
            exportFailed -> context.getString(R.string.l7_report_retry)
            else -> ""
        }, error = exportFailed)
        export.isEnabled = !exporting
        val running = L7DebugOverlayService.isRunning
        state.setValue(context.getString(R.string.l7_debug_status,
            context.getString(if (Settings.canDrawOverlays(context)) R.string.l7_debug_granted else R.string.l7_debug_denied),
            context.getString(if (running) R.string.l7_debug_running else R.string.l7_debug_stopped)))
        start.isEnabled = !running
        stop.isEnabled = running
        start.setFeedback(if (running) context.getString(R.string.l7_debug_already_running) else "")
        stop.setFeedback(if (running) "" else context.getString(R.string.l7_debug_not_running))
    }

    fun showExportFailure() {
        exportFailed = true
        update(false)
    }

}
