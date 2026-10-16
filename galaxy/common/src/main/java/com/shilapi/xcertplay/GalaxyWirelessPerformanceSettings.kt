package com.shilapi.xcertplay

import android.content.Context
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R

/** 无线优化为独立应用偏好，下一次连接生效，车型模板不覆盖。 */
internal object GalaxyWirelessPerformanceSettings {
    const val SCAN = "wifi_scan_pause_enabled"
    const val LOCK = "wifi_high_performance_enabled"
    fun enabled(context: Context, key: String) = context.getSharedPreferences("xcertplay_airplay", 0)
        .getBoolean(key, true)

    fun add(context: Context, parent: LinearLayout, direct: Boolean, editable: () -> Boolean): L7SettingRow {
        val key = if (direct) SCAN else LOCK
        val title = if (direct) R.string.galaxy_wifi_scan_pause else R.string.galaxy_wifi_performance_lock
        val description = if (direct) R.string.galaxy_wifi_scan_pause_hint else R.string.galaxy_wifi_performance_lock_hint
        lateinit var row: L7SettingRow
        row = L7Components.switchRow(context, context.getString(title), context.getString(description),
            enabled(context, key)) { selected ->
            if (editable()) context.getSharedPreferences("xcertplay_airplay", 0).edit().putBoolean(key, selected).apply()
            row.setSwitchChecked(enabled(context, key))
        }.apply { isEnabled = editable() }
        parent.addView(row)
        return row
    }
}
