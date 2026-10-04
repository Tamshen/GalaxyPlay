package com.shilapi.xcertplay

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.EditText
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R

internal class L7ProbeUiState {
    var filter = 0
    var query = ""
    var selectedReport: String? = null
}

/** 结果行只展示名称与结论，权限原名和有限证据在同套模态框中展开。 */
internal class L7ProbeResultsView(
    private val activity: Activity,
    parent: LinearLayout,
    private val state: L7ProbeUiState,
    private val exporter: L7ProbeExporter,
    private val onNavigate: (String) -> Unit,
) {
    private val labels = L7ProbeLabels(activity)
    private val panel = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private var renderedRevision = -1L
    init { parent.addView(panel); update() }

    fun update(force: Boolean = false) {
        if (!force && renderedRevision == L7ProbeRunner.revision) return
        renderedRevision = L7ProbeRunner.revision
        panel.removeAllViews()
        val report = if (state.selectedReport == null) L7ProbeRunner.current else
            L7ProbeRunner.history.find { it.id == state.selectedReport }
                ?: L7ProbeRunner.current?.takeIf { it.id == state.selectedReport }
        if (report == null) {
            panel.addView(L7Components.text(activity, labels.text(R.string.l7_probe_idle), secondary = true))
            return
        }
        L7SettingsSection.add(panel, labels.text(R.string.l7_probe_environment),
            footer = labels.text(R.string.l7_probe_limited)) { card ->
            val historical = state.selectedReport != null
            card.addView(L7SettingRow(activity, if (historical) activity.getString(R.string.l7_probe_historical,
                labels.time(report.started)) else labels.time(report.started), labels.summary(report)).apply {
                setValue(labels.phase(report))
                if (L7ProbeRunner.environment != null && report.environment != L7ProbeRunner.environment)
                    setFeedback(labels.text(R.string.l7_probe_stale))
            })
            val filters = listOf(R.string.l7_probe_all, R.string.l7_probe_verified, R.string.l7_probe_restricted,
                R.string.l7_probe_pending, R.string.l7_probe_not_run).map(labels::text)
            card.addView(L7Components.actionRow(activity, labels.text(R.string.l7_probe_filter)) {
                L7Dialogs.builder(activity).setTitle(R.string.l7_probe_filter)
                    .setItems(filters.toTypedArray()) { _, index -> state.filter = index; update(true) }.show()
            }.apply { setValue(filters[state.filter.coerceIn(filters.indices)]) })
            card.addView(L7Components.actionRow(activity, labels.text(R.string.l7_probe_search)) { search() }
                .apply { setValue(state.query) })
        }
        val items = report.items.filter { item ->
            (state.query.isBlank() || item.name.contains(state.query, true) || labels.name(item).contains(state.query, true)) &&
                when (state.filter) {
                    1 -> item.result == L7ProbeOutcome.VERIFIED
                    2 -> item.result == L7ProbeOutcome.DENIED
                    3 -> item.result !in setOf(L7ProbeOutcome.VERIFIED, L7ProbeOutcome.DENIED) && item.reason != "NOT_RUN"
                    4 -> item.reason == "NOT_RUN"
                    else -> true
                }
        }
        actions(report)
        if (items.isEmpty()) panel.addView(L7Components.text(activity, labels.text(R.string.l7_probe_empty), secondary = true))
        else L7SettingsSection.add(panel, labels.text(R.string.l7_probe_results)) { card ->
            items.forEach { item -> card.addView(L7Components.actionRow(activity, labels.name(item), labels.reason(item)) {
                showItem(item)
            }) }
        }
    }

    private fun actions(report: L7ProbeReport) {
        L7SettingsSection.add(panel, labels.text(R.string.l7_probe_logs), footer = labels.text(R.string.l7_probe_report_scope)) { card ->
            card.addView(L7Components.actionRow(activity, labels.text(R.string.l7_probe_copy)) {
                activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                    ClipData.newPlainText(labels.text(R.string.l7_probe_title), labels.readable(report)))
                L7Notice.show(activity, labels.text(R.string.l7_probe_copied))
            })
            for ((title, choose) in listOf(R.string.l7_probe_export to false, R.string.l7_probe_export_choose to true)) {
                card.addView(L7Components.actionRow(activity, labels.text(title)) { exporter.export(report, choose) }
                    .apply { isEnabled = report.phase != L7ProbePhase.RUNNING })
            }
            card.addView(L7Components.actionRow(activity, labels.text(R.string.l7_probe_delete)) {
                L7Dialogs.builder(activity).setTitle(R.string.l7_probe_delete).setMessage(R.string.l7_probe_delete_confirm)
                    .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.l7_probe_delete) { _, _ ->
                        if (L7ProbeRunner.delete(activity, report.id)) onNavigate("settings-debug-history")
                        else L7Notice.show(activity, labels.text(R.string.l7_probe_busy))
                    }.show()
            }.apply { isEnabled = !L7ProbeRunner.busy })
        }
    }

    private fun showItem(item: L7ProbeItem) {
        val next = if (item.domain == "PERMISSION") R.string.l7_probe_next_permission else R.string.l7_probe_next_query
        L7Dialogs.builder(activity).setTitle(labels.name(item)).setMessage(
            "${labels.reason(item)}\n\n${labels.time(item.time)}\n\n${labels.text(next)}\n\n" +
                "${labels.text(R.string.l7_probe_facts)}\n${item.json().toString(2)}")
            .setNegativeButton(R.string.close, null)
            .setPositiveButton(R.string.l7_probe_recheck_item) { _, _ ->
                if (L7ProbeRunner.start(activity, L7ProbeEnvironment.window(activity), item.id)) {
                    state.selectedReport = null
                    onNavigate("settings-debug-results")
                } else L7Notice.show(activity, labels.text(R.string.l7_probe_busy))
            }.show()
    }

    private fun search() {
        val input = EditText(activity).apply { setSingleLine(); setText(state.query); hint = labels.text(R.string.l7_probe_search) }
        L7Ui.text(input)
        L7Dialogs.builder(activity).setTitle(R.string.l7_probe_search).setView(input)
            .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.done) { _, _ ->
                state.query = input.text.toString().trim().take(160); update(true)
            }.show()
    }
}
