package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R
import java.io.Closeable

/** 用户先监听，再为已收到的输入标注实物键；页面不发送测试按键。 */
internal class L7SteeringDebugPage(private val activity: Activity, parent: LinearLayout,
    onLogs: () -> Unit) : Closeable {
    private val controller = SteeringListening.controller
    private val handler = Handler(Looper.getMainLooper())
    private val status: L7SettingRow
    private val toggle: L7SettingRow
    private val records: L7SettingsCard
    private val details: L7DebugDetails
    private lateinit var guide: L7DebugGuide
    private var guideBaseline: Long
        get() = guide.memory.getLong("baseline")
        set(value) { guide.memory.putLong("baseline", value) }
    private var dialog: AlertDialog? = null
    private var prompted = -1L
    private var revision = -1L
    private var disposed = false
    private var foreground = true
    private var noInput: L7SettingRow? = null
    private val effects = mutableListOf<L7SettingRow>()
    private var effectSample: SteeringListeningController.Sample? = null
    private val tick = object : Runnable {
        override fun run() { if (!disposed && foreground) { update(); handler.postDelayed(this, 200) } }
    }

    init {
        guide = L7DebugGuide(activity, parent, "STEERING", listOf(
            L7DebugGuide.Step(R.string.debug_guide_prepare, R.string.debug_guide_prepare_body),
            L7DebugGuide.Step(R.string.debug_guide_listen, R.string.debug_guide_listen_body,
                { toggle }, { controller.active() && controller.snapshot().run > guideBaseline },
                { guideBaseline = controller.snapshot().run }),
        ) + SteeringListeningController.LABELS.take(7).mapIndexed { index, key ->
            L7DebugGuide.Step(KEY_TITLES[index], KEY_BODIES[index], { status }, {
                controller.snapshot().let { it.run > guideBaseline && it.samples.lastOrNull { sample -> sample.label == key }?.let { sample ->
                    guide.memory.getLong("effect_$key", -1) == sample.id
                } == true }
            })
        } + listOf(
            L7DebugGuide.Step(R.string.debug_guide_stop, R.string.debug_guide_stop_body,
                { toggle }, { !controller.active() }),
            L7DebugGuide.Step(R.string.debug_guide_logs, R.string.debug_guide_logs_body)
        ), { controller.snapshot().let { longArrayOf(it.run, it.pending?.id ?: it.samples.lastOrNull()?.id ?: 0) } })
        lateinit var statusRow: L7SettingRow
        lateinit var toggleRow: L7SettingRow
        L7SettingsSection.add(parent, text(R.string.l7_listen_step), description = text(R.string.l7_listen_intro)) { card ->
            statusRow = L7SettingRow(activity, text(R.string.l7_listen_status)).also(card::addView)
            toggleRow = L7Components.actionRow(activity, text(R.string.l7_listen_start)) {
                if (disposed || !foreground) return@actionRow
                if (controller.active()) { controller.stop("USER"); dialog?.dismiss(); dialog = null }
                else if (L7Agreement.canUse(activity) && !L7AppExit.exiting) {
                    L7SteeringDiagnostics.initialize(activity)
                    controller.start(L7AudioTemplates.model(activity).id)
                }
                update()
            }.also(card::addView)
            card.addView(L7Components.actionRow(activity, text(R.string.l7_listen_no_input), text(R.string.l7_listen_no_input_hint)) {
                if (!disposed && foreground && controller.active()) {
                    L7SteeringDiagnostics.record("SteeringListen stage=USER_NO_INPUT run=${controller.snapshot().run} model=${L7AudioTemplates.model(activity).id}")
                    noInput?.setFeedback(text(R.string.l7_listen_no_input_saved))
                    guide.missing()
                }
            }.apply { isEnabled = false; noInput = this })
        }
        status = statusRow; toggle = toggleRow
        L7SettingsSection.add(parent, text(R.string.debug_guide_scenario_result)) { card ->
            effects.add(L7Components.actionRow(activity, text(R.string.debug_guide_ok)) { observeEffect(true) }.also(card::addView))
            effects.add(L7Components.actionRow(activity, text(R.string.debug_guide_bad)) { observeEffect(false) }.also(card::addView))
        }
        records = L7SettingsSection.add(parent, text(R.string.l7_listen_records), footer = text(R.string.l7_listen_saved)) {}
        details = L7DebugDetails(activity, parent)
        L7SettingsSection.add(parent, text(R.string.l7_listen_finish)) { card ->
            card.addView(L7Components.actionRow(activity, text(R.string.l7_steering_logs), text(R.string.l7_listen_upload_hint)) {
                background(); onLogs()
            })
        }
        update(); handler.postDelayed(tick, 200)
    }

    fun update() {
        if (disposed) return
        guide.update()
        controller.poll(L7AudioTemplates.model(activity).id)
        val value = controller.snapshot()
        val active = controller.active()
        val key = SteeringListeningController.LABELS.take(7).getOrNull(guide.currentStep - 2)
        effectSample = value.samples.lastOrNull { it.label == key && value.run > guideBaseline }
        effects.forEach { it.isEnabled = active && key != null && effectSample != null }
        val toggleTitle = text(if (active) R.string.l7_listen_stop else R.string.l7_listen_start)
        if (toggle.titleView.text.toString() != toggleTitle) toggle.titleView.text = toggleTitle
        toggle.isEnabled = L7Agreement.canUse(activity) && !L7AppExit.exiting
        noInput?.isEnabled = value.phase == SteeringListeningController.Phase.LISTENING
        status.setValue(text(when (value.phase) {
            SteeringListeningController.Phase.IDLE -> R.string.l7_listen_idle
            SteeringListeningController.Phase.LISTENING -> R.string.l7_listen_waiting
            SteeringListeningController.Phase.LABEL -> R.string.l7_listen_received
            SteeringListeningController.Phase.STOPPED -> R.string.l7_listen_stopped
        }))
        status.setFeedback(L7AudioModelConfirmation.name(activity, L7AudioTemplates.model(activity)))
        if (revision != value.revision) {
            revision = value.revision
            records.removeAllViews()
            if (value.samples.isEmpty()) records.addView(L7SettingRow(activity, text(R.string.l7_listen_empty)))
            value.samples.asReversed().forEach { sample ->
                val index = SteeringListeningController.LABELS.indexOf(sample.label)
                records.addView(L7SettingRow(activity, activity.getString(R.string.l7_listen_sample, sample.id),
                    activity.resources.getStringArray(R.array.l7_steering_test_keys).getOrNull(index) ?: "").apply {
                    setValue(activity.getString(R.string.l7_listen_bound, sample.traces.size))
                })
            }
        }
        val events = L7SteeringDiagnostics.store.snapshot().events.takeLast(24)
        details.update(events.joinToString("\n") { "#${it.id} ${it.source} ${it.stage} ${it.detail}" })
        val pending = value.pending
        if (foreground && pending != null && prompted != pending.id && dialog == null && !guide.isPromptShowing) prompt(pending)
        if (!active) { dialog?.dismiss(); dialog = null }
    }

    private fun observeEffect(normal: Boolean) {
        val sample = effectSample ?: return
        val key = SteeringListeningController.LABELS.take(7).getOrNull(guide.currentStep - 2) ?: return
        val value = controller.snapshot()
        if (!foreground || disposed || value.run <= guideBaseline ||
            value.samples.lastOrNull { it.label == key }?.id != sample.id) return
        guide.memory.putLong("effect_$key", sample.id)
        L7SteeringDiagnostics.record("SteeringListen stage=USER_EFFECT run=${value.run} sample=${sample.id} key=$key result=${if (normal) "EXPECTED" else "MISSING_OR_ABNORMAL"} origin=MANUAL")
        update()
    }

    private fun prompt(sample: SteeringListeningController.Sample) {
        prompted = sample.id
        dialog = L7Dialogs.builder(activity).setTitle(R.string.l7_listen_received)
            .setMessage(R.string.l7_listen_question)
            .setItems(activity.resources.getStringArray(R.array.l7_steering_test_keys).dropLast(1).toTypedArray()) { clicked, index ->
                if (!disposed && dialog === clicked && dialog?.isShowing == true)
                    controller.answer(sample.id, SteeringListeningController.LABELS[index])
                clicked.dismiss(); dialog = null; update()
            }.setNegativeButton(R.string.l7_listen_skip) { _, _ -> controller.answer(sample.id, "SKIP"); dialog = null; update() }
            .setOnCancelListener { controller.answer(sample.id, "SKIP"); dialog = null; update() }.show()
    }

    fun background() { guide.background(); foreground = false; handler.removeCallbacks(tick); controller.stop("PAGE_BACKGROUND"); dialog?.dismiss(); dialog = null }
    fun resume() { if (!disposed) { guide.resume(); foreground = true; handler.removeCallbacks(tick); handler.post(tick) } }
    override fun close() { guide.close(); disposed = true; background(); handler.removeCallbacks(tick) }
    private fun text(id: Int) = activity.getString(id)
    private companion object {
        val KEY_TITLES = listOf(R.string.debug_key_left, R.string.debug_key_right, R.string.debug_key_play,
            R.string.debug_key_voice_short, R.string.debug_key_voice_long, R.string.debug_key_volume_up, R.string.debug_key_volume_down)
        val KEY_BODIES = listOf(R.string.debug_key_left_body, R.string.debug_key_right_body, R.string.debug_key_play_body,
            R.string.debug_key_voice_short_body, R.string.debug_key_voice_long_body, R.string.debug_key_volume_up_body, R.string.debug_key_volume_down_body)
    }
}
