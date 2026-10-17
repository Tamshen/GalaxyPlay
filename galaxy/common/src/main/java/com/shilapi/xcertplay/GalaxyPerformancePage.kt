package com.shilapi.xcertplay

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R
import java.util.Locale

/** 沿用宿主低频刷新，只读取服务快照；打开详情不会自行创建采样任务。 */
internal class GalaxyPerformancePage(private val activity: Activity, parent: LinearLayout) {
    private val toggle: L7SettingRow
    private val overlay: L7SettingRow
    private val state: L7SettingRow
    private val deep: L7SettingRow
    private val cpu: L7SettingRow
    private val memory: L7SettingRow
    private val video: L7SettingRow
    private val costs: L7SettingRow
    private val extras: L7SettingRow
    private var failed = false
    private var lastSample: GalaxyPerformanceSample? = null
    private var lastState = -1
    private fun text(id: Int) = activity.getString(id)
    init {
        lateinit var start: L7SettingRow; lateinit var show: L7SettingRow
        lateinit var status: L7SettingRow; lateinit var depth: L7SettingRow
        L7SettingsSection.add(parent, text(R.string.performance_title), description = text(R.string.performance_hint)) { card ->
            status = L7SettingRow(activity, text(R.string.performance_state)).also(card::addView)
            start = L7Components.actionRow(activity, text(R.string.performance_start)) {
                if (GalaxyPerformanceState.running) GalaxyPerformanceService.stop(activity)
                else failed = GalaxyPerformanceService.start(activity).isFailure
                update()
            }.also(card::addView)
            show = L7Components.switchRow(activity, text(R.string.performance_overlay), text(R.string.performance_overlay_hint),
                GalaxyPerformanceState.overlayWanted) { value -> GalaxyPerformanceService.overlay(activity, value); update() }.also(card::addView)
            card.addView(L7Components.actionRow(activity, text(R.string.l7_debug_permission), text(R.string.performance_permission_hint)) {
                failed = runCatching { activity.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${activity.packageName}"))) }.isFailure
                update()
            })
            depth = L7Components.actionRow(activity, text(R.string.performance_deep), text(R.string.performance_deep_hint)) {
                failed = runCatching { GalaxyPerformanceService.deep(activity) }.isFailure; update()
            }.also(card::addView)
        }
        toggle = start; overlay = show; state = status; deep = depth
        lateinit var c: L7SettingRow; lateinit var m: L7SettingRow; lateinit var v: L7SettingRow
        lateinit var cost: L7SettingRow; lateinit var extra: L7SettingRow
        L7SettingsSection.add(parent, text(R.string.performance_details), footer = text(R.string.performance_measurement_hint)) { card ->
            c = L7SettingRow(activity, "CPU", text(R.string.performance_cpu_hint)).also(card::addView)
            m = L7SettingRow(activity, text(R.string.performance_memory), text(R.string.performance_memory_hint)).also(card::addView)
            v = L7SettingRow(activity, text(R.string.performance_fps), text(R.string.performance_fps_hint)).also(card::addView)
            cost = L7SettingRow(activity, text(R.string.performance_cost)).also(card::addView)
            extra = L7SettingRow(activity, text(R.string.performance_extra)).also(card::addView)
        }
        cpu = c; memory = m; video = v; costs = cost; extras = extra
        update()
    }
    fun update() {
        val running = GalaxyPerformanceState.running
        val sample = GalaxyPerformanceState.latest
        val flags = (if (running) 1 else 0) or (if (GalaxyPerformanceState.overlayWanted) 2 else 0) or
            (if (GalaxyPerformanceState.overlayPermission) 4 else 0) or (if (GalaxyPerformanceState.displayFailed) 8 else 0) or
            (if (failed) 16 else 0)
        if (sample === lastSample && flags == lastState) return
        lastSample = sample; lastState = flags
        val title = text(if (running) R.string.performance_stop else R.string.performance_start)
        if (toggle.titleView.text.toString() != title) toggle.titleView.text = title
        overlay.setSwitchChecked(GalaxyPerformanceState.overlayWanted)
        deep.isEnabled = running
        state.setValue(text(when {
            !running -> R.string.performance_stopped
            sample?.deep == true -> R.string.performance_deep_running
            !GalaxyPerformanceState.overlayPermission && GalaxyPerformanceState.overlayWanted -> R.string.performance_fallback
            else -> R.string.performance_running
        }))
        state.setFeedback(if (failed || GalaxyPerformanceState.displayFailed) text(R.string.performance_failed) else "", failed || GalaxyPerformanceState.displayFailed)
        fun number(value: Double?) = value?.let { String.format(Locale.US, "%.1f", it) } ?: "—"
        fun bytes(value: Long?) = number(value?.div(1048576.0))
        val input = sample?.input
        cpu.setValue("${number(sample?.cpuCorePercent)} %")
        memory.setValue("Java ${bytes(input?.javaBytes)} MiB · Native ${bytes(input?.nativeBytes)} MiB\n" +
            "PSS ${number(input?.pssKiB?.div(1024.0))} MiB · RSS ${number(input?.rssKiB?.div(1024.0))} MiB")
        video.setValue(sample?.video?.joinToString("\n") {
            "${it.value.stream}: RX ${number(it.receivedFps)} · FPS ${number(it.presentedFps)} · ${it.value.codec ?: "—"} · " +
                (if (it.value.outputWidth > 0 && it.value.outputHeight > 0) "${it.value.outputWidth}×${it.value.outputHeight}" else "—")
        }?.ifEmpty { text(R.string.performance_no_stream) } ?: text(R.string.performance_no_stream))
        costs.setValue(activity.getString(R.string.performance_cost_value,
            number(sample?.sampleNs?.div(1_000_000.0)), number(sample?.displayNs?.div(1_000_000.0))))
        costs.setFeedback(if (sample?.overBudget == true) text(R.string.performance_budget) else "", sample?.overBudget == true)
        extras.setValue("GC ${input?.gcCount ?: "—"} · ${input?.gcTimeMs ?: "—"} ms\n" +
            "${sample?.threadCpu?.joinToString { "${it.first}: ${number(it.second)}%" }?.ifEmpty { "—" } ?: "—"}")
    }
}
