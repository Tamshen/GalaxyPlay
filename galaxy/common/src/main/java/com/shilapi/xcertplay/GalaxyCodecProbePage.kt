package com.shilapi.xcertplay

import android.app.Activity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R
import java.io.Closeable

/** 开始后播放固定动态样例；人工画面判断绑定最后一项运行，明细默认收起。 */
internal class GalaxyCodecProbePage(private val activity: Activity, parent: LinearLayout,
    private val controller: GalaxyCodecProbeController, private val occupied: () -> Boolean,
    onLogs: () -> Unit) : Closeable, SurfaceHolder.Callback {
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
    init {
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
            card.addView(preview, LinearLayout.LayoutParams(-1, -2))
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
        if (occupied() && controller.busy && controller.notice != "CONNECT_REQUEST") {
            controller.stop("CONNECT_REQUEST")
            return
        }
        val busy = controller.busy
        val current = controller.decoder()
        sample.setValue(controller.video.title + " · 640×360 · 30 fps · 2 s")
        decoder.setValue(current?.let(::label) ?: text(R.string.codec_probe_no_hardware))
        method.setValue(text(controller.method.title))
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
                item.hardwarePassed -> R.string.codec_probe_hardware_ok
                item.decoded -> R.string.codec_probe_software_ok
                item.reason in listOf("CANCELLED", "BACKGROUND", "SURFACE_LOST", "CONNECT_REQUEST") -> R.string.codec_probe_cancelled
                else -> R.string.codec_probe_failed
            }).let { if (!item.decoded && item.reason.isNotEmpty()) it + " · " + text(item.stage.title) else it }, if (item.outputs < 0) "—" else item.outputs.toString(), item.elapsedMs)
        }.ifEmpty { text(R.string.codec_probe_no_results) })
        observed.titleView.text = activity.getString(R.string.codec_probe_observation_for, controller.results.lastOrNull()?.let { it.video.title + " · " + text(it.method.title) }.orEmpty())
        observed.setValue(text(when (controller.observed) {
            true -> R.string.codec_probe_observed_yes
            false -> R.string.codec_probe_observed_no
            null -> R.string.codec_probe_observed_pending
        }))
        val canObserve = !busy && controller.results.lastOrNull()?.outputs?.let { it > 0 } == true
        listOf(visible, abnormal, observed).forEach { it.visibility = if (canObserve) View.VISIBLE else View.GONE }
        details.update(controller.lines.joinToString("\n"))
    }
    override fun surfaceCreated(holder: SurfaceHolder) { update() }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { update() }
    override fun surfaceDestroyed(holder: SurfaceHolder) { controller.stop("SURFACE_LOST") }
    fun background() { foreground = false; controller.stop("BACKGROUND"); update() }
    fun resume() { foreground = true; update() }
    override fun close() {
        closed = true
        controller.changed = null
        controller.stop("SURFACE_LOST")
        preview.holder.removeCallback(this)
    }
}
