package com.shilapi.xcertplay

import android.app.Activity
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R
import java.io.Closeable

/** 复现操作由用户执行，观察绑定当前引导步骤；沿用默认诊断日志，不自动改系统或上传。 */
internal class GalaxyScenarioDebugPage(private val activity: Activity, private val parent: LinearLayout,
    private val onLogs: () -> Unit) : Closeable {
    private val state = (activity as? GalaxySettingsActivity)?.debugGuides?.state("SCENARIO") ?: L7DebugGuideStore.State()
    private var guide: L7DebugGuide? = null
    private lateinit var action: L7SettingRow
    private lateinit var result: L7SettingRow
    private val observations = mutableListOf<L7SettingRow>()
    private val eligible get() = state.run > 0 && state.index in 1..SCRIPTS[selected].size
    private var selected: Int
        get() = state.memory.getInt("selected").coerceIn(SCRIPTS.indices)
        set(value) { state.memory.putInt("selected", value) }
    init { render() }
    private fun render() {
        parent.removeAllViews(); observations.clear()
        L7SettingsSection.add(parent) { card ->
            card.addView(L7Components.actionRow(activity, text(R.string.debug_scenario_choose), text(TITLES[selected])) {
                L7Components.select(activity, text(R.string.debug_scenario_choose), TITLES.map(::text), selected,
                    text(R.string.language_apply)) { value ->
                    guide?.reset(); guide?.close(); selected = value; state.memory.remove("performed"); state.memory.remove("observed"); render()
                }
            })
        }
        val script = SCRIPTS[selected]
        guide = L7DebugGuide(activity, parent, "SCENARIO", listOf(
            L7DebugGuide.Step(R.string.debug_guide_prepare, R.string.debug_guide_prepare_body)
        ) + script.map { (title, body) ->
            L7DebugGuide.Step(title, body, { action }, {
                state.memory.getBoolean("performed") && state.memory.containsKey("observed")
            }, { state.memory.remove("performed"); state.memory.remove("observed"); update() })
        } + L7DebugGuide.Step(R.string.debug_guide_logs, R.string.debug_guide_logs_body), {
            longArrayOf(selected.toLong(), if (CarPlayBackgroundSession.hasSession()) 1L else 0L)
        })
        L7SettingsSection.add(parent, text(R.string.debug_guide_scenario_action)) { card ->
            action = L7Components.actionRow(activity, text(R.string.debug_guide_mark)) {
                if (!eligible) return@actionRow
                state.memory.putBoolean("performed", true); event("ACTION_PERFORMED"); update()
            }.also(card::addView)
        }
        L7SettingsSection.add(parent, text(R.string.debug_guide_scenario_result)) { card ->
            result = L7SettingRow(activity, text(R.string.debug_guide_scenario_result)).also(card::addView)
            observations.add(L7Components.actionRow(activity, text(R.string.debug_guide_ok)) { observe(true) }.also(card::addView))
            observations.add(L7Components.actionRow(activity, text(R.string.debug_guide_bad)) { observe(false) }.also(card::addView))
            card.addView(L7Components.actionRow(activity, text(R.string.l7_report_logs), text(R.string.debug_guide_logs_body)) { onLogs() })
        }
        update()
    }
    private fun observe(normal: Boolean) {
        if (!eligible || !state.memory.getBoolean("performed")) return
        state.memory.putBoolean("observed", normal)
        event(if (normal) "EXPECTED" else "MISSING_OR_ABNORMAL"); update()
    }
    private fun event(phase: String) {
        runCatching { L7DebugLog.record("DEBUG_SCENARIO run=${state.run} step=${state.index + 1} scenario=$selected phase=$phase origin=MANUAL monoMs=${android.os.SystemClock.elapsedRealtime()} sessionActive=${CarPlayBackgroundSession.hasSession()}") }
    }
    fun update() {
        guide?.update()
        if (::action.isInitialized) action.isEnabled = eligible
        observations.forEach { it.isEnabled = eligible && state.memory.getBoolean("performed") }
        if (::result.isInitialized) result.setValue(text(if (!state.memory.containsKey("observed")) R.string.debug_guide_wait
            else if (state.memory.getBoolean("observed")) R.string.debug_guide_ok else R.string.debug_guide_bad))
    }
    fun background() { guide?.background() }
    fun resume() { guide?.resume(); update() }
    override fun close() { guide?.close() }
    private fun text(id: Int) = activity.getString(id)
    private companion object {
        val TITLES = listOf(R.string.connect_with_usb, R.string.built_in_car_hotspot, R.string.codec_probe_title, R.string.audio, R.string.debug_scenario_bt_control)
        val SCRIPTS = listOf(
            listOf(R.string.debug_scenario_connect_usb to R.string.debug_guide_usb_body, R.string.debug_scenario_usb_recover to R.string.debug_scenario_usb_recover_body),
            listOf(R.string.built_in_car_hotspot to R.string.debug_guide_wifi_body, R.string.debug_scenario_wifi_recover to R.string.debug_scenario_wifi_recover_body),
            listOf(R.string.codec_probe_title to R.string.debug_guide_video_body, R.string.debug_scenario_video_switch to R.string.debug_scenario_video_switch_body),
            listOf(R.string.debug_scenario_music to R.string.debug_scenario_music_body, R.string.debug_scenario_nav to R.string.debug_scenario_nav_body,
                R.string.debug_scenario_call to R.string.debug_scenario_call_body, R.string.debug_scenario_siri to R.string.debug_scenario_siri_body),
            listOf(R.string.debug_scenario_bt_control to R.string.debug_guide_bt_body, R.string.debug_scenario_bt_control to R.string.debug_scenario_bt_control_body,
                R.string.debug_scenario_call to R.string.debug_scenario_call_body)
        )
    }
}
