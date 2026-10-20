package com.shilapi.xcertplay

import android.content.Context
import android.widget.LinearLayout
import android.widget.Toast
import com.shilapi.xcertplay.host.R

/** 三项即时保存为独立偏好，下一次连接生效，取消选择不会写入。 */
internal object GalaxyMediaPerformanceSettings {
    fun add(context: Context, parent: LinearLayout) {
        val host = if (context is GalaxyConfigurationContext) context.baseContext else context
        val labels = listOf(host.getString(R.string.media_performance_default), host.getString(R.string.media_performance_oem))
        L7SettingsSection.add(parent, host.getString(R.string.media_performance_title),
            footer = host.getString(R.string.media_performance_reconnect_hint)) { card ->
            val titles = listOf(R.string.media_performance_music, R.string.media_performance_video_buffer, R.string.media_performance_low_latency)
            val details = listOf(R.string.media_performance_music_hint, R.string.media_performance_video_buffer_hint,
                R.string.media_performance_low_latency_hint)
            GalaxyMediaPerformancePreferences.Item.entries.forEachIndexed { index, item ->
                fun selected() = if (GalaxyMediaPerformancePreferences.enabled(host, item)) 1 else 0
                lateinit var row: L7SettingRow
                row = L7Components.actionRow(host, host.getString(titles[index]), host.getString(details[index])) {
                    L7Components.select(host, host.getString(titles[index]), labels, selected(),
                        host.getString(R.string.l7_save_next_connection)) { choice ->
                        runCatching { GalaxyMediaPerformancePreferences.save(host, item, choice == 1) }
                            .onSuccess { row.setValue(labels[selected()]) }
                            .onFailure { Toast.makeText(host, R.string.l7_template_save_failed, Toast.LENGTH_SHORT).show() }
                    }
                }.apply { setValue(labels[selected()]) }
                card.addView(row)
            }
        }
    }
}
