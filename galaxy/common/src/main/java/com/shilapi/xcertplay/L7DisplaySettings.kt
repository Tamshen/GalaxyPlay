package com.shilapi.xcertplay

import android.app.Activity
import android.provider.Settings
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R

/** 应用字号、悬浮导航与投屏参数分别分组，沿用各自已有保存时机。 */
internal object L7DisplaySettings {
    fun add(activity: Activity, parent: LinearLayout) {
        fun text(id: Int) = activity.getString(id)
        L7SettingsSection.add(parent, text(R.string.l7_section_app_ui), footer = text(R.string.l7_ui_density_hint)) {
            L7UiDensitySettings.add(activity, it)
        }
        L7SettingsSection.add(parent, text(R.string.l7_section_floating), footer = text(R.string.l7_floating_hint)) { card ->
            card.addView(L7Components.switchRow(activity, text(R.string.l7_desktop_title), text(R.string.l7_desktop_hint),
                L7DesktopNavigation.enabled(activity)) { L7DesktopNavigation.setEnabled(activity, it) })
            if (!Settings.canDrawOverlays(activity)) card.addView(L7Components.actionRow(activity,
                text(R.string.l7_desktop_permission), text(R.string.l7_desktop_permission_hint)) {
                L7DesktopNavigation.permission(activity)
            })
            val presets = L7FloatingNavigationPreferences.transparencyPresets
            L7Components.choice(card, text(R.string.l7_floating_transparency), presets.map { "$it%" },
                presets.indexOf(L7FloatingNavigationPreferences.transparency(activity)), reconnects = false) {
                L7FloatingNavigationPreferences.saveTransparency(activity, presets[it])
            }
        }
        L7SettingsSection.add(parent, text(R.string.l7_section_projection), footer = text(R.string.l7_setting_apply_hint)) { card ->
            val panel = L7DisplayGeometry.panelSize
            card.addView(L7SettingRow(activity, text(R.string.carplay_size), text(R.string.l7_physical_size_description)).apply {
                setValue(activity.getString(R.string.l7_physical_size_value, panel.widthMm, panel.heightMm))
            })
            L7Components.choice(card, text(R.string.resolution), listOf(text(R.string.resolution_native),
                text(R.string.s_80_lighter_load), text(R.string.s_60_lightest_load)),
                listOf(10, 8, 6).indexOf(AirPlayPersistence.loadDisplayScaleTenths(activity))) {
                AirPlayPersistence.saveDisplayScaleTenths(activity, listOf(10, 8, 6)[it])
            }
            L7Components.choice(card, text(R.string.frame_rate), listOf(text(R.string.s_30_fps_lighter_load),
                text(R.string.s_60_fps_smoother_motion)), if (AirPlayPersistence.loadFps(activity) == 60) 1 else 0) {
                AirPlayPersistence.saveFps(activity, if (it == 1) 60 else 30)
            }
            GalaxyVideoDecoderSettings.add(activity, card)
            card.addView(L7Components.switchRow(activity, text(R.string.efficient_video),
                text(R.string.use_hevc_leave_off_for_the_widest_head_unit_compatibility),
                AirPlayPersistence.loadHevcEnabled(activity)) { AirPlayPersistence.saveHevcEnabled(activity, it) })
            card.addView(L7Components.switchRow(activity, text(R.string.full_screen),
                text(R.string.hide_the_car_s_system_bars_while_carplay_is_open),
                AirPlayPersistence.loadHideTopBar(activity) && AirPlayPersistence.loadHideBottomBar(activity)) {
                AirPlayPersistence.saveHideTopBar(activity, it)
                AirPlayPersistence.saveHideBottomBar(activity, it)
            })
        }
    }
}
