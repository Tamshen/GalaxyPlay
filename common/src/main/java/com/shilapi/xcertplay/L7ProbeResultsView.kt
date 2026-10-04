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
    fun showCurrent() { filter = 0; query = ""; selectedReport = null }
}

/** 结果行只展示名称与结论，权限原名和有限证据在同套模态框中展开。 */
internal class L7ProbeResultsView(
    private val activity: Activity,
    parent: LinearLayout,
    private val state: L7ProbeUiState,
    private val exporter: L7ProbeExporter,
    private val tasks: L7DebugTasks,
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
        panel.addView(L7Components.text(activity,
            "${labels.phase(report)} · ${labels.time(report.started)}\n${labels.summary(report)}", secondary = true).apply { textSize = 15f })
        if (state.selectedReport != null) panel.addView(L7Components.text(activity, labels.text(R.string.l7_probe_historical_hint), true))
        if (L7ProbeRunner.environment != null && report.environment != L7ProbeRunner.environment)
            panel.addView(L7Components.text(activity, labels.text(R.string.l7_probe_stale), true))
        val filters = listOf(R.string.l7_probe_all, R.string.l7_probe_filter_supported, R.string.l7_probe_no_permission,
            R.string.l7_probe_error, R.string.l7_probe_unsupported, R.string.l7_probe_pending, R.string.l7_probe_not_run).map(labels::text)
        panel.addView(LinearLayout(activity).apply {
            setPadding(0, dp(8), 0, dp(8))
            addView(control(filters[state.filter.coerceIn(filters.indices)]) {
                L7Dialogs.builder(activity).setTitle(R.string.l7_probe_filter)
                    .setItems(filters.toTypedArray()) { _, index -> state.filter = index; update(true) }.show()
            }.apply { contentDescription = labels.text(R.string.l7_probe_filter) }, slot())
            addView(control(labels.text(R.string.l7_probe_search_short)) { search() }, slot())
            addView(control(labels.text(R.string.l7_probe_again)) {
                if (tasks.collect()) { state.showCurrent(); update(true) }
            }.apply { isEnabled = !L7ProbeRunner.busy }, slot())
            addView(control(labels.text(R.string.l7_probe_report_actions)) { actions(report) }, slot())
        })
        panel.addView(L7Components.text(activity, labels.text(R.string.l7_probe_table_hint), true).apply {
            textSize = 13f; setPadding(0, 0, 0, dp(8))
        })
        if (state.query.isNotBlank()) panel.addView(L7Components.text(activity, state.query, true))
        val items = report.items.filter { item ->
            (state.query.isBlank() || item.name.contains(state.query, true) || labels.name(item).contains(state.query, true)) &&
                L7ProbeStatus.of(item).matches(state.filter)
        }
        if (items.isEmpty()) panel.addView(L7Components.text(activity, labels.text(R.string.l7_probe_empty), secondary = true))
        else panel.addView(L7ProbeTable(activity, items, ::showItem))
    }

    private fun actions(report: L7ProbeReport) {
        val options = listOf(R.string.l7_probe_copy, R.string.l7_probe_export, R.string.l7_probe_export_choose, R.string.l7_probe_delete)
        L7Dialogs.builder(activity).setTitle(R.string.l7_probe_report_actions)
            .setItems(options.map(labels::text).toTypedArray()) { _, index ->
                when (index) {
                    0 -> {
                        activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                            ClipData.newPlainText(labels.text(R.string.l7_probe_title), labels.readable(report)))
                        L7Notice.show(activity, labels.text(R.string.l7_probe_copied))
                    }
                    1, 2 -> if (report.phase != L7ProbePhase.RUNNING) exporter.export(report, index == 2)
                        else L7Notice.show(activity, labels.text(R.string.l7_probe_busy))
                    3 -> L7Dialogs.builder(activity).setTitle(R.string.l7_probe_delete).setMessage(R.string.l7_probe_delete_confirm)
                        .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.l7_probe_delete) { _, _ ->
                            if (L7ProbeRunner.delete(activity, report.id)) onNavigate("settings-debug-history")
                            else L7Notice.show(activity, labels.text(R.string.l7_probe_busy))
                        }.show()
                }
            }.show()
    }

    private fun showItem(item: L7ProbeItem) {
        val next = if (item.domain == "PERMISSION") R.string.l7_probe_next_permission else R.string.l7_probe_next_query
        L7Dialogs.builder(activity).setTitle(labels.name(item)).setMessage(
            "${labels.reason(item)}\n\n${labels.time(item.time)}\n\n${labels.text(next)}\n\n" +
                "${labels.text(R.string.l7_probe_facts)}\n${item.json().toString(2)}")
            .setNegativeButton(R.string.close, null)
            .setPositiveButton(R.string.l7_probe_recheck_item) { _, _ ->
                if (tasks.collect(item.id)) {
                    state.showCurrent()
                    onNavigate("settings-debug-results")
                }
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

    private fun dp(value: Int) = L7Components.dp(activity, value)
    private fun slot() = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) }
    private fun control(title: String, action: () -> Unit) = L7Components.actionButton(activity, title, click = action).apply {
        L7Ui.button(this, compact = true)
    }
}
