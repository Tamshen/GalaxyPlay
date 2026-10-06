package com.shilapi.xcertplay

import android.app.Activity
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R

/** 调试入口只读取本地历史；用户明确开始后才扫描，日志服务继续由原组件管理。 */
internal class L7DebugPage(
    private val activity: Activity,
    private val parent: LinearLayout,
    private val page: String,
    private val state: L7ProbeUiState,
    exporter: L7ProbeExporter,
    private val tasks: L7DebugTasks,
    private val onNavigate: (String) -> Unit,
) {
    private val labels = L7ProbeLabels(activity)
    private var results: L7ProbeResultsView? = null
    private var summary: L7SettingRow? = null
    private var start: L7SettingRow? = null
    private var view: L7SettingRow? = null
    private var revision = -1L
    private var guide: L7DebugGuide? = null
    private var guideBaseline: String?
        get() = guide?.memory?.getString("baseline")
        set(value) { guide?.memory?.putString("baseline", value) }
    fun background() { guide?.background() }
    fun resume() { guide?.resume() }
    fun close() { guide?.close() }

    init {
        L7ProbeRunner.load(activity)
        when (page) {
            "settings-debug-results" -> results = L7ProbeResultsView(activity, parent, state, exporter, tasks, onNavigate)
            "settings-debug-history" -> history()
            else -> home()
        }
        update()
    }

    private fun home() {
        guide = L7DebugGuide(activity, parent, "ENVIRONMENT", listOf(
            L7DebugGuide.Step(R.string.debug_guide_prepare, R.string.debug_guide_prepare_body),
            L7DebugGuide.Step(R.string.debug_guide_collect, R.string.debug_guide_collect_body, { start }, {
                L7ProbeRunner.current?.let { it.id != guideBaseline && it.phase != L7ProbePhase.RUNNING && !L7ProbeRunner.busy } == true
            }, { guideBaseline = L7ProbeRunner.current?.id }),
            L7DebugGuide.Step(R.string.debug_guide_logs, R.string.debug_guide_logs_body)
        ))
        L7SettingsSection.add(parent, labels.text(R.string.l7_debug_choose), description = labels.text(R.string.l7_debug_choose_hint)) { card ->
            listOf(
                Triple(R.string.debug_guide_scenario, R.string.debug_guide_scenario_hint, "settings-debug-scenario"),
                Triple(R.string.l7_steering_title, R.string.l7_debug_goal_steering, "settings-debug-steering"),
                Triple(R.string.codec_probe_title, R.string.codec_probe_hint, "settings-debug-codec"),
                Triple(R.string.l7_voice_title, R.string.l7_debug_goal_voice, "settings-debug-voice"),
                Triple(R.string.l7_report_media_title, R.string.l7_debug_goal_media, "settings-debug-media"),
                Triple(R.string.l7_report_navigation_title, R.string.l7_debug_goal_hud, "settings-debug-navigation"),
            ).forEach { (title, hint, page) ->
                card.addView(L7Components.actionRow(activity, labels.text(title), labels.text(hint)) { onNavigate(page) })
            }
        }
        L7SettingsSection.add(parent, labels.text(R.string.l7_probe_environment), footer = labels.text(R.string.l7_probe_intro)) { card ->
            summary = L7SettingRow(activity, labels.text(R.string.l7_probe_idle)).also(card::addView)
            start = L7Components.actionRow(activity, labels.text(R.string.l7_probe_start)) {
                if (tasks.collect()) {
                    state.showCurrent()
                    update()
                }
            }.also(card::addView)
            view = L7Components.actionRow(activity, labels.text(R.string.l7_probe_results)) {
                state.selectedReport = if (L7ProbeRunner.current == null) L7ProbeRunner.history.firstOrNull()?.id else null
                onNavigate("settings-debug-results")
            }.also(card::addView)
        }
        L7SettingsSection.add(parent) { card ->
            card.addView(L7Components.actionRow(activity, labels.text(R.string.l7_probe_history)) { onNavigate("settings-debug-history") })
        }
    }

    fun update() {
        guide?.update()
        results?.update()
        if (page == "settings-debug-history" && revision != L7ProbeRunner.revision) history()
        revision = L7ProbeRunner.revision
        val current = L7ProbeRunner.current
        val latest = current ?: L7ProbeRunner.history.firstOrNull()
        summary?.apply {
            val title = latest?.let { if (current == null) activity.getString(R.string.l7_probe_historical,
                labels.time(it.started)) else labels.phase(it) } ?: labels.text(R.string.l7_probe_idle)
            if (titleView.text.toString() != title) titleView.text = title
            setValue(latest?.let(labels::summary) ?: "")
            setFeedback(when {
                L7ProbeRunner.storageFailed -> labels.text(R.string.l7_probe_storage_failed)
                L7ProbeRunner.logFailed -> labels.text(R.string.l7_probe_log_failed)
                current?.phase == L7ProbePhase.RUNNING -> activity.getString(R.string.l7_probe_progress,
                    current.items.count { it.reason != "NOT_RUN" }, current.expected)
                L7ProbeRunner.busy -> labels.text(if (current == null) R.string.l7_probe_loading else R.string.l7_probe_cleanup)
                current != null -> activity.getString(R.string.l7_probe_logged, L7ProbeLog.batch(current))
                else -> ""
            })
        }
        start?.isEnabled = !L7ProbeRunner.busy
        start?.titleView?.let { title ->
            val text = labels.text(if (latest == null) R.string.l7_probe_start else R.string.l7_probe_recollect)
            if (title.text.toString() != text) title.text = text
        }
        view?.isEnabled = latest != null
    }

    private fun history() {
        parent.removeAllViews()
        val reports = L7ProbeRunner.history
        L7SettingsSection.add(parent, labels.text(R.string.l7_probe_history), footer = labels.text(R.string.l7_probe_historical_hint)) { card ->
            if (reports.isEmpty()) card.addView(L7SettingRow(activity, labels.text(R.string.l7_probe_history_empty)))
            reports.forEach { report -> card.addView(L7Components.actionRow(activity, labels.time(report.started), labels.summary(report)) {
                state.showCurrent()
                state.selectedReport = report.id
                onNavigate("settings-debug-results")
            }.apply { setValue(labels.phase(report)) }) }
        }
    }
}
