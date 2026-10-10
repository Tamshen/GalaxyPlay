package com.shilapi.xcertplay

import android.content.Context
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.ManualHotspotValidation

/** 连接偏好独立保存，两项输入确认后一次更新完整凭据。 */
internal object GalaxyHotspotSettings {
    fun add(context: Context, parent: LinearLayout) {
        val preferences = context.getSharedPreferences("xcertplay_airplay", 0)
        lateinit var row: L7SettingRow
        fun label() = preferences.getString("manual_hotspot_ssid", "").orEmpty()
            .ifEmpty { context.getString(R.string.profile_unset) }
        row = L7Components.actionRow(context, context.getString(R.string.config_hotspot_title),
            context.getString(R.string.config_hotspot_hint)) {
            L7HotspotEditor.show(context, preferences.getString("manual_hotspot_ssid", "").orEmpty(),
                preferences.getString("manual_hotspot_passphrase", "").orEmpty(),
                R.string.save_details) { name, password ->
                preferences.edit().putString("manual_hotspot_ssid", name).putString("manual_hotspot_passphrase", password)
                    .putString("manual_hotspot_security", ManualHotspotValidation.securityFor(password).name)
                    .putString("manual_hotspot_band", "AUTO").putInt("manual_hotspot_channel", 0).commit()
                row.setValue(label())
            }
        }.apply { setValue(label()) }
        parent.addView(row)
    }
}
