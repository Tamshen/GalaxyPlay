package com.shilapi.xcertplay

import android.app.Activity
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R

/** 语言、大小和显示偏好独立保存，不进入车型草稿。 */
internal object GalaxyApplicationSettingsPage {
    fun add(activity: Activity, parent: LinearLayout) {
        parent.addView(L7Components.note(activity, activity.getString(R.string.application_settings_hint)))
        L7SettingsSection.add(parent, activity.getString(R.string.language_section_title)) { card ->
            card.addView(L7Components.valueRow(activity, activity.getString(R.string.language_app_language),
                AppLocale.displayName(activity, AppLocale.preference(activity))) { AppLocale.showPicker(activity) })
        }
        L7DisplaySettings.addApplication(activity, parent)
        L7SettingsSection.add(parent, activity.getString(R.string.config_icon_size),
            footer = activity.getString(R.string.l7_setting_apply_hint)) { card ->
            GalaxyConfigurationChoice.add(activity, card, "ui_scale_percent", R.string.config_icon_size,
                listOf(100, 125, 150), listOf(R.string.config_size_default, R.string.config_size_large, R.string.config_size_larger))
        }
        L7SettingsSection.add(parent, activity.getString(R.string.profile_night_mode)) { card ->
            GalaxyProfileFieldsView.add(activity, card, R.string.l7_section_app_ui,
                setOf("carplay_night_mode", "ambient_delay_seconds", "ambient_lux_threshold"))
        }
    }
}
