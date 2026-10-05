package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R
import java.io.Closeable

/** 页面只展示与派发显式动作；离开页面可观察原车显示，测试由独立组件持有并限时。 */
internal class L7ReportingTestPage(private val activity: Activity, parent: LinearLayout,
    private val kind: L7ReportingKind, onLogs: () -> Unit) : Closeable {
    private var disposed = false
    private var dialog: AlertDialog? = null
    private var previous: Pair<Long, Boolean>? = null
    private lateinit var status: L7SettingRow
    private lateinit var start: L7SettingRow
    private lateinit var end: L7SettingRow
    private lateinit var evidence: L7SettingRow
    private val controls = mutableListOf<L7SettingRow>()
    private val observations = mutableListOf<L7SettingRow>()
    private fun text(id: Int) = activity.getString(id)

    init {
        L7SettingsSection.add(parent, text(R.string.l7_report_status),
            description = text(if (kind == L7ReportingKind.MEDIA) R.string.l7_report_media_hint else R.string.l7_report_navigation_hint)) { card ->
            status = L7SettingRow(activity, text(R.string.l7_report_status)).also(card::addView)
            start = L7Components.actionRow(activity, text(R.string.l7_report_start)) { confirm() }.also(card::addView)
            end = L7Components.actionRow(activity, text(R.string.l7_report_end)) {
                if (L7ReportingTests.snapshot().kind == kind) L7ReportingTests.stop("USER")
                update()
            }.also(card::addView)
        }
        L7SettingsSection.add(parent, text(R.string.l7_report_actions), footer = text(R.string.l7_report_lifetime)) { card ->
            val actions = if (kind == L7ReportingKind.MEDIA) listOf(
                R.string.l7_report_track to L7ReportingAction.TRACK,
                R.string.l7_report_play_pause to L7ReportingAction.PLAY_PAUSE,
                R.string.l7_report_progress to L7ReportingAction.PROGRESS,
                R.string.l7_report_cover to L7ReportingAction.COVER,
            ) else listOf(R.string.l7_report_road to L7ReportingAction.ROAD, R.string.l7_report_refresh to L7ReportingAction.REFRESH)
            actions.forEach { (label, action) ->
                controls.add(L7Components.actionRow(activity, text(label)) {
                    L7ReportingTests.action(kind, action); update()
                }.also(card::addView))
            }
        }
        L7SettingsSection.add(parent, text(R.string.l7_report_evidence), footer = text(R.string.l7_report_evidence_hint)) { card ->
            evidence = L7SettingRow(activity, text(R.string.l7_report_evidence)).also(card::addView)
            observations.add(L7Components.actionRow(activity, text(R.string.l7_report_visible)) {
                L7ReportingTests.observe(kind, true); update()
            }.also(card::addView))
            observations.add(L7Components.actionRow(activity, text(R.string.l7_report_missing)) {
                L7ReportingTests.observe(kind, false); update()
            }.also(card::addView))
            card.addView(L7Components.actionRow(activity, text(R.string.l7_report_logs)) { onLogs() })
        }
        update()
    }

    private fun confirm() {
        if (disposed || !L7Agreement.canUse(activity) || CarPlayBackgroundSession.hasSession()) return
        dialog?.dismiss()
        dialog = L7Dialogs.builder(activity)
            .setTitle(if (kind == L7ReportingKind.MEDIA) R.string.l7_report_media_title else R.string.l7_report_navigation_title)
            .setMessage(R.string.l7_report_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.l7_report_start) { clicked, _ ->
                if (!disposed && dialog === clicked && dialog?.isShowing == true &&
                    !activity.isFinishing && !activity.isDestroyed) L7ReportingTests.start(activity, kind)
                update()
            }.show()
    }

    fun update() {
        if (disposed) return
        val value = L7ReportingTests.snapshot()
        val phone = CarPlayBackgroundSession.hasSession()
        val revision = value.revision to phone
        if (previous == revision) return
        previous = revision
        val own = value.kind == kind
        val busy = value.phase in setOf(L7ReportingTestController.Phase.RUNNING, L7ReportingTestController.Phase.STOPPING)
        val running = own && value.phase == L7ReportingTestController.Phase.RUNNING
        val phase = if (!own) {
            if (busy) R.string.l7_report_other_running else R.string.l7_report_idle
        } else when (value.phase) {
            L7ReportingTestController.Phase.IDLE -> R.string.l7_report_idle
            L7ReportingTestController.Phase.RUNNING -> R.string.l7_report_running
            L7ReportingTestController.Phase.STOPPING -> R.string.l7_report_stopping
            L7ReportingTestController.Phase.STOPPED -> R.string.l7_report_stopped
        }
        status.setValue(text(phase))
        status.setFeedback(if (phone) text(R.string.l7_report_phone_busy)
            else if (own) activity.getString(R.string.l7_report_run, value.run) else "")
        start.isEnabled = !busy && !phone && L7Agreement.canUse(activity) && !L7AppExit.exiting
        end.isEnabled = running
        controls.forEach { it.isEnabled = running && !phone }
        observations.forEach { it.isEnabled = own && value.run > 0 }
        val ended = own && value.phase == L7ReportingTestController.Phase.STOPPED
        listOf(if (ended) R.string.l7_report_cleared else R.string.l7_report_visible,
            if (ended) R.string.l7_report_residual else R.string.l7_report_missing).forEachIndexed { index, id ->
            val title = observations[index].titleView
            if (title.text.toString() != text(id)) title.text = text(id)
        }
        evidence.setFeedback(if (own && value.lines.isNotEmpty()) {
            val first = value.firstIssue?.let { activity.getString(R.string.l7_report_first_issue, it) }
            (listOfNotNull(first) + value.lines.takeLast(4).filter { it != value.firstIssue }).joinToString("\n")
        }
            else text(R.string.l7_report_no_evidence))
    }

    fun background() { dialog?.dismiss(); dialog = null }
    override fun close() { disposed = true; background() }
}
