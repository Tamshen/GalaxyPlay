package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.widget.LinearLayout
import android.widget.Toast
import com.shilapi.xcertplay.host.R
import java.io.Closeable

/** 真实播放与无声样例共用反馈入口；确认时核对会话及曲目版本，取消不写观察结果。 */
internal class GalaxyMediaReportFeedback(private val activity: Activity, parent: LinearLayout) : Closeable {
    private var closed = false
    private var dialog: AlertDialog? = null
    private var picker: AlertDialog? = null
    private lateinit var status: L7SettingRow
    private val rows = mutableListOf<L7SettingRow>()
    private val seenLabels = listOf(R.string.media_feedback_unobserved, R.string.media_feedback_correct,
        R.string.media_feedback_missing, R.string.media_feedback_stale, R.string.media_feedback_wrong)
    private fun text(id: Int) = activity.getString(id)

    init {
        L7SettingsSection.add(parent, text(R.string.media_feedback_title),
            description = text(R.string.media_feedback_hint)) { card ->
            status = L7SettingRow(activity, text(R.string.l7_report_current_sample)).also(card::addView)
            listOf(R.string.media_feedback_center, R.string.media_feedback_cluster).forEachIndexed { index, title ->
                rows += L7Components.actionRow(activity, text(title)) {
                    show(GalaxyMediaReportEvidence.Target.entries[index])
                }.also(card::addView)
            }
        }
        update()
    }

    fun update() {
        if (closed) return
        val active = GalaxyMediaReportEvidence.active
        val snapshot = active?.snapshot()
        val available = active?.available() == true
        rows.forEach { it.isEnabled = available }
        status.setValue(if (available && snapshot != null) sample(snapshot) else text(R.string.media_feedback_wait))
        status.setFeedback(if (available && snapshot?.facts?.get("observedRevision") == snapshot?.revision)
            text(R.string.media_feedback_saved) else "")
    }
    private fun sample(snapshot: GalaxyMediaReportEvidence.Snapshot) = activity.getString(R.string.media_feedback_sample,
        snapshot.value.title?.take(80)?.takeIf(String::isNotBlank) ?: text(R.string.media_feedback_no_title),
        text(if (!snapshot.value.playbackKnown) R.string.media_feedback_unknown_playback
            else if (snapshot.value.playing) R.string.l7_report_playing else R.string.l7_report_paused),
        snapshot.value.elapsedMillis?.let { activity.getString(R.string.media_feedback_progress, it / 1000) }
            ?: text(R.string.media_feedback_unknown_progress))

    private fun show(target: GalaxyMediaReportEvidence.Target) {
        if (closed || !L7Agreement.canUse(activity)) return
        val owner = GalaxyMediaReportEvidence.active ?: return
        if (!owner.available()) return
        val expected = owner.snapshot()
        val selected = IntArray(4)
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val labels = seenLabels.map(::text)
        listOf(R.string.media_feedback_song, R.string.media_feedback_artwork, R.string.media_feedback_playback)
            .forEachIndexed { index, title ->
                lateinit var row: L7SettingRow
                row = L7Components.valueRow(activity, text(title), labels[0]) {
                    choose(text(title), labels, selected[index]) { value ->
                        selected[index] = value
                        row.setValue(labels[value])
                        dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = selected.any { it != 0 }
                    }
                }
                body.addView(row)
            }
        val comparisonLabels = listOf(R.string.media_feedback_no_comparison, R.string.media_feedback_other_works,
            R.string.media_feedback_other_fails).map(::text)
        lateinit var comparisonRow: L7SettingRow
        comparisonRow = L7Components.valueRow(activity, text(R.string.media_feedback_comparison), comparisonLabels[0]) {
            choose(text(R.string.media_feedback_comparison), comparisonLabels, selected[3]) { value ->
                selected[3] = value
                comparisonRow.setValue(comparisonLabels[value])
            }
        }
        body.addView(comparisonRow)
        background()
        val next = L7Dialogs.builder(activity)
            .setTitle(if (target == GalaxyMediaReportEvidence.Target.CENTER) R.string.media_feedback_center else R.string.media_feedback_cluster)
            .setMessage(sample(expected)).setView(body).setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { clicked, _ ->
                if (!closed && dialog === clicked && dialog?.isShowing == true && !activity.isFinishing && !activity.isDestroyed) {
                    val seen = selected.take(3).map { GalaxyMediaReportEvidence.Seen.entries[it] }
                    val accepted = owner.observe(expected, target, seen[0], seen[1], seen[2],
                        GalaxyMediaReportEvidence.Comparison.entries[selected[3]])
                    Toast.makeText(activity, if (accepted) R.string.media_feedback_saved else R.string.l7_report_sample_changed,
                        Toast.LENGTH_SHORT).show()
                    update()
                }
            }.create()
        dialog = next
        next.show()
        next.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = false
    }

    private fun choose(title: String, labels: List<String>, current: Int, save: (Int) -> Unit) {
        var selected = current
        picker?.dismiss()
        picker = L7Dialogs.builder(activity).setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), current) { _, index -> selected = index }
            .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.save) { clicked, _ ->
                if (!closed && picker === clicked && dialog?.isShowing == true) save(selected)
            }.show()
    }
    fun background() { picker?.dismiss(); picker = null; dialog?.dismiss(); dialog = null }
    override fun close() { closed = true; background() }
}
