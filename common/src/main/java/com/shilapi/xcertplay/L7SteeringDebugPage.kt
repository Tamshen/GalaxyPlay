package com.shilapi.xcertplay

import android.app.Activity
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R

/** 页面仅观察现有控制链路；标记和清屏均为本地操作。 */
internal class L7SteeringDebugPage(private val activity: Activity, parent: LinearLayout,
                                  onLogs: () -> Unit) {
    private val store = L7SteeringDiagnostics.store
    private val status: L7SettingRow
    private val states: L7SettingRow
    private val records: L7SettingsCard
    private var revision = -1L

    init {
        lateinit var statusRow: L7SettingRow
        lateinit var statesRow: L7SettingRow
        L7SettingsSection.add(parent, text(R.string.l7_steering_status), footer = text(R.string.l7_steering_hint)) { card ->
            statusRow = L7SettingRow(activity, text(R.string.l7_steering_session)).also(card::addView)
            statesRow = L7SettingRow(activity, text(R.string.l7_steering_chain)).also(card::addView)
        }
        status = statusRow
        states = statesRow
        L7SettingsSection.add(parent, text(R.string.l7_steering_markers)) { card ->
            card.addView(L7Components.actionRow(activity, text(R.string.l7_steering_mark_failed)) { mark("USER_FAILURE") })
            card.addView(L7Components.actionRow(activity, text(R.string.l7_steering_mark_ok)) { mark("USER_SUCCESS") })
            card.addView(L7Components.actionRow(activity, text(R.string.l7_steering_logs)) { onLogs() })
            card.addView(L7Components.actionRow(activity, text(R.string.l7_steering_clear), text(R.string.l7_steering_clear_hint)) {
                store.clear(); update()
            })
        }
        records = L7SettingsSection.add(parent, text(R.string.l7_steering_events), footer = text(R.string.l7_steering_events_hint)) { }
        update()
    }

    private fun mark(reason: String) { L7SteeringDiagnostics.begin("manual-marker", -1).step("MARK", reason); update() }

    fun update() {
        val snapshot = store.snapshot()
        status.setValue(activity.getString(if (snapshot.connected) R.string.l7_steering_connected else R.string.l7_steering_disconnected,
            snapshot.generation))
        states.setValue((snapshot.states.entries.map { "${it.key}: ${it.value}" } +
            "Bluetooth: ${L7BluetoothAudioSettings.status}").joinToString("\n"))
        if (revision == snapshot.revision) return
        revision = snapshot.revision
        records.removeAllViews()
        val groups = snapshot.events.groupBy { it.id }.values.toList().takeLast(12).asReversed()
        if (groups.isEmpty()) records.addView(L7SettingRow(activity, text(R.string.l7_steering_empty)))
        groups.forEach { events ->
            val first = events.first()
            records.addView(L7SettingRow(activity, "#${first.id} · ${first.source}",
                "generation=${first.generation} index=${first.command} monoMs=${first.atMs}").apply {
                setValue(events.joinToString("\n") { "+${it.elapsedMs} ms  ${it.stage}${if (it.detail.isEmpty()) "" else " · ${it.detail}"}" })
            })
        }
    }

    private fun text(id: Int) = activity.getString(id)
}
