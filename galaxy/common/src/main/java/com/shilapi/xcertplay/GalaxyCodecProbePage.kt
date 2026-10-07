package com.shilapi.xcertplay

import android.app.Activity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R
import java.io.Closeable

/** 开始后播放固定动态样例；人工画面判断绑定最后一项运行，明细默认收起。 */
internal class GalaxyCodecProbePage(private val activity: Activity, parent: LinearLayout,
    private val controller: GalaxyCodecProbeController, private val occupied: () -> Boolean,
    onLogs: () -> Unit) : Closeable, SurfaceHolder.Callback {
    private lateinit var guide: L7DebugGuide
    private var guideBaseline: Long
        get() = guide.memory.getLong("baseline")
        set(value) { guide.memory.putLong("baseline", value) }
    private var foreground = true
    private var closed = false
    private lateinit var sample: L7SettingRow
    private lateinit var decoder: L7SettingRow
    private lateinit var method: L7SettingRow
    private lateinit var software: L7SettingRow
    private lateinit var start: L7SettingRow
    private lateinit var all: L7SettingRow
    private lateinit var stop: L7SettingRow
    private lateinit var status: L7SettingRow
    private lateinit var result: L7SettingRow
    private lateinit var observed: L7SettingRow
    private lateinit var visible: L7SettingRow
    private lateinit var abnormal: L7SettingRow
    private val details: L7DebugDetails
    private val preview = object : SurfaceView(activity) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            setMeasuredDimension(width, width * 9 / 16)
        }
    }
    private val bufferPreview = L7Typography.text(activity, activity.getString(R.string.codec_probe_buffer_preview),
        L7Typography.Role.DESCRIPTION).apply {
        gravity = Gravity.CENTER
        val padding = L7Components.dp(activity, 24)
        setPadding(padding, padding, padding, padding)
        L7Ui.bind(this) {
            setTextColor(activity.getColor(R.color.product_ui_text))
            setBackgroundColor(activity.getColor(R.color.product_ui_surface))
        }
    }
    init {
        guide = L7DebugGuide(activity, parent, "CODEC", listOf(
            L7DebugGuide.Step(R.string.debug_guide_prepare, R.string.debug_guide_prepare_body),
            L7DebugGuide.Step(R.string.debug_guide_codec, R.string.debug_guide_codec_body, { start },
                { !controller.busy && controller.results.lastOrNull()?.run?.let { it > guideBaseline } == true },
                { guideBaseline = controller.results.lastOrNull()?.run ?: 0 }),
            L7DebugGuide.Step(R.string.debug_guide_codec_result, R.string.debug_guide_codec_result_body,
                { if (controller.results.lastOrNull()?.let { it.method.surfaceOutput && it.outputs > 0 } == true) visible else result },
                { !controller.busy && controller.results.lastOrNull()?.let { it.run > guideBaseline &&
                    (!it.method.surfaceOutput || it.outputs <= 0 || controller.observed != null) } == true }),
            L7DebugGuide.Step(R.string.debug_guide_logs, R.string.debug_guide_logs_body)
        ), { longArrayOf(controller.results.lastOrNull()?.run ?: 0) })
        L7SettingsSection.add(parent, text(R.string.codec_probe_choose), footer = text(R.string.codec_probe_scope)) { card ->
            sample = L7Components.actionRow(activity, text(R.string.codec_probe_sample)) {
                choose(text(R.string.codec_probe_sample), CodecProbeVideo.entries.map { it.title }, controller.video.ordinal) {
                    controller.video = CodecProbeVideo.entries[it]; controller.selected = ""; update()
                }
            }.also(card::addView)
            decoder = L7Components.actionRow(activity, text(R.string.codec_probe_decoder)) {
                val available = controller.available
                if (available.isNotEmpty()) choose(text(R.string.codec_probe_decoder), available.map { label(it) },
                    available.indexOfFirst { it.name == controller.decoder()?.name }.coerceAtLeast(0)) {
                    controller.selected = available[it].name; update()
                }
            }.also(card::addView)
            method = L7Components.actionRow(activity, text(R.string.codec_probe_method)) {
                choose(text(R.string.codec_probe_method), CodecProbeMethod.entries.map { text(it.title) }, controller.method.ordinal) {
                    controller.method = CodecProbeMethod.entries[it]; update()
                }
            }.also(card::addView)
            software = L7Components.switchRow(activity, text(R.string.codec_probe_software), text(R.string.codec_probe_software_hint), false) {
                if (!controller.busy) { controller.allowSoftware = it; controller.selected = ""; update() }
            }.also(card::addView)
        }
        L7SettingsSection.add(parent, text(R.string.codec_probe_run), footer = text(R.string.codec_probe_preview_hint)) { card ->
            status = L7SettingRow(activity, text(R.string.codec_probe_status)).also(card::addView)
            preview.holder.setFixedSize(640, 360)
            preview.holder.addCallback(this)

            preview.contentDescription = text(R.string.codec_probe_preview)
            val previewFrame = FrameLayout(activity).apply {
                addView(preview, FrameLayout.LayoutParams(-1, -2))
                addView(bufferPreview, FrameLayout.LayoutParams(-1, -1))
            }
            card.addView(previewFrame, LinearLayout.LayoutParams(-1, -2))
            start = L7Components.actionRow(activity, text(R.string.codec_probe_start)) { run(false) }.also(card::addView)
            all = L7Components.actionRow(activity, text(R.string.codec_probe_all)) { run(true) }.also(card::addView)
            stop = L7Components.actionRow(activity, text(R.string.codec_probe_stop)) { controller.stop() }.also(card::addView)
        }
        L7SettingsSection.add(parent, text(R.string.codec_probe_results), footer = text(R.string.codec_probe_result_hint)) { card ->
            result = L7SettingRow(activity, text(R.string.codec_probe_result_list)).also(card::addView)
            observed = L7SettingRow(activity, text(R.string.codec_probe_observation)).also(card::addView)
            visible = L7Components.actionRow(activity, text(R.string.codec_probe_visible)) { controller.observe(true) }.also(card::addView)
            abnormal = L7Components.actionRow(activity, text(R.string.codec_probe_abnormal)) { controller.observe(false) }.also(card::addView)
            card.addView(L7Components.actionRow(activity, text(R.string.codec_probe_logs)) { onLogs() })
        }
        details = L7DebugDetails(activity, parent)
        controller.changed = ::update
        update()
    }
    private fun text(id: Int) = activity.getString(id)
    private fun label(value: CodecProbeDecoder) = value.name + " · " + text(when {
        value.hardware && !value.software -> R.string.codec_probe_hardware
        value.software -> R.string.codec_probe_software_kind
        else -> R.string.codec_probe_unknown_kind
    })
    private fun choose(title: String, values: List<String>, current: Int, changed: (Int) -> Unit) {
        if (closed || controller.busy) return
        L7Components.select(activity, title, values, current, text(R.string.language_apply)) { if (!closed) changed(it) }
    }
    private fun run(all: Boolean) {
        if (closed || !foreground || controller.busy || occupied() || !L7Agreement.canUse(activity)) return
        controller.start(preview.holder.surface, all)
        update()
    }
    fun update() {
        if (closed) return
        guide.update()
        if (occupied() && controller.busy && controller.notice != "CONNECT_REQUEST") {
            controller.stop("CONNECT_REQUEST")
            return
        }
        val busy = controller.busy
        val current = controller.decoder()
        // 覆盖旧帧而不隐藏 SurfaceView，避免提示切换销毁底层 Surface 并中断测试。
        val outputMethod = if (busy) controller.currentMethod else controller.method
        bufferPreview.visibility = if (outputMethod?.surfaceOutput == false) View.VISIBLE else View.GONE
        sample.setValue(controller.video.title + " · 640×360 · 30 fps · 2 s")
        decoder.setValue(current?.let(::label) ?: text(R.string.codec_probe_no_hardware))
        method.setValue(text(controller.method.title))
        method.setDescription(text(controller.method.description))
        decoder.setFeedback(if (GalaxyCodecProbeRecipe(controller.method).byType) text(R.string.codec_probe_system_choice) else "")
        software.setSwitchChecked(controller.allowSoftware)
        listOf(sample, decoder, method, software).forEach { it.isEnabled = foreground && !busy }
        start.isEnabled = foreground && !busy && current != null && preview.holder.surface.isValid && !occupied() && controller.notice != "PROCESS_UNCONFIRMED"
        all.isEnabled = start.isEnabled
        stop.isEnabled = busy
        status.setValue(if (busy) activity.getString(R.string.codec_probe_running,
            text(controller.currentMethod!!.title), text(controller.stage.title)) else text(R.string.codec_probe_idle))
        status.setFeedback(when {
            controller.notice == "PROCESS_UNCONFIRMED" -> text(R.string.codec_probe_cleanup_unconfirmed)
            occupied() -> text(R.string.codec_probe_occupied)
            current == null -> text(R.string.codec_probe_no_hardware_hint)
            controller.notice.isNotEmpty() -> text(R.string.codec_probe_interrupted)
            else -> ""
        })
        result.setValue(controller.results.joinToString("\n\n") { item ->
            activity.getString(R.string.codec_probe_summary, item.video.title + " · " + text(item.method.title), text(when {
                item.reason in listOf("UnsupportedVideo", "QtiDecoderRequired") -> R.string.codec_probe_not_applicable
                item.decoded && !item.method.surfaceOutput -> R.string.codec_probe_buffer_ok
                item.hardwarePassed -> R.string.codec_probe_hardware_ok
                item.decoded -> R.string.codec_probe_software_ok
                item.reason in listOf("CANCELLED", "BACKGROUND", "SURFACE_LOST", "CONNECT_REQUEST") -> R.string.codec_probe_cancelled
                else -> R.string.codec_probe_failed
            }).let { if (!item.decoded && item.reason.isNotEmpty()) it + " · " + text(item.stage.title) else it }, if (item.outputs < 0) "—" else item.outputs.toString(), item.elapsedMs)
        }.ifEmpty { text(R.string.codec_probe_no_results) })
        observed.titleView.text = activity.getString(R.string.codec_probe_observation_for, controller.results.lastOrNull()?.let { it.video.title + " · " + text(it.method.title) }.orEmpty())
        observed.setValue(text(when {
            controller.results.lastOrNull()?.method?.surfaceOutput == false -> R.string.codec_probe_buffer_observation
            controller.observed == true -> R.string.codec_probe_observed_yes
            controller.observed == false -> R.string.codec_probe_observed_no
            else -> R.string.codec_probe_observed_pending
        }))
        val hasOutput = !busy && controller.results.lastOrNull()?.outputs?.let { it > 0 } == true
        val canObserve = hasOutput && controller.results.lastOrNull()?.method?.surfaceOutput == true
        listOf(visible, abnormal).forEach { it.visibility = if (canObserve) View.VISIBLE else View.GONE }
        observed.visibility = if (hasOutput) View.VISIBLE else View.GONE
        details.update(controller.lines.joinToString("\n"))
    }
    override fun surfaceCreated(holder: SurfaceHolder) { update() }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { update() }
    override fun surfaceDestroyed(holder: SurfaceHolder) { controller.stop("SURFACE_LOST") }
    fun background() { guide.background(); foreground = false; controller.stop("BACKGROUND"); update() }
    fun resume() { guide.resume(); foreground = true; update() }
    override fun close() {
        guide.close()
        closed = true
        controller.changed = null
        controller.stop("SURFACE_LOST")
        preview.holder.removeCallback(this)
    }
}
