package com.shilapi.xcertplay

import android.content.Context
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R

/** 参数按模块直接呈现；快捷预设与精确编辑共用一个入口。 */
internal object GalaxyConfigurationSections {
    fun add(context: Context, parent: LinearLayout, group: Int) {
        fun text(id: Int) = context.getString(id)
        when (group) {
            0 -> L7SettingsSection.add(parent) { card ->
                quickChoice(context, card, "display_scale_percent", R.string.config_quality,
                    listOf(100, 80, 60), listOf(R.string.config_quality_clear, R.string.config_quality_balanced, R.string.config_quality_light))
                quickChoice(context, card, "display_fps", R.string.config_smoothness,
                    listOf(30, 60), listOf(R.string.config_smoothness_standard, R.string.config_smoothness_high))
                quickChoice(context, card, "media_buffer_ms", R.string.config_music_stability,
                    com.shilapi.xcertplay.media.MediaAudioBuffer.wirelessPresets,
                    listOf(R.string.config_music_fast, R.string.config_music_stable,
                        R.string.config_music_safest))
                card.addView(L7Components.switchRow(context, text(R.string.config_bluetooth_music),
                    text(R.string.config_bluetooth_hint), AirPlayPersistence.loadBluetoothMediaExclusive(context)) {
                    AirPlayPersistence.saveBluetoothMediaExclusive(context, it)
                })
            }
            1 -> L7SettingsSection.add(parent) { card ->
                GalaxyProfileFieldsView.add(context, card, R.string.config_page_connection)
            }
            2 -> L7SettingsSection.add(parent, description = text(R.string.config_steering_hint)) { card ->
                GalaxyProfileFieldsView.add(context, card, R.string.config_page_steering)
            }
            3 -> {
                val audio = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                parent.addView(audio)
                L7AudioSettings.page(context, audio) { title, current, role, apply ->
                    L7AudioRouteDialog.show(context, title, current, role, apply)
                }
            }
            4 -> L7SettingsSection.add(parent) { card ->
                GalaxyProfileFieldsView.add(context, card, R.string.l7_section_projection,
                    setOf("hide_top_bar", "hide_bottom_bar", "right_hand_drive"))
                GalaxyVideoDecoderSettings.add(context, card)
                GalaxyProfileFieldsView.add(context, card, R.string.l7_section_projection,
                    setOf("hevc_enabled", "hevc_software_decoder", "safe_area_draw_outside", "adapt_pip_resolution"))
            }
            else -> L7SettingsSection.add(parent, description = text(R.string.config_reporting_hint)) { card ->
                GalaxyProfileFieldsView.add(context, card, R.string.config_page_reporting)
            }
        }
        if (group == 0) GalaxyMediaPerformanceSettings.add(context, parent)
        parent.addView(L7Components.note(context, text(R.string.config_page_next_connection)))
    }

    private fun quickChoice(context: Context, parent: LinearLayout, key: String, title: Int,
                            values: List<Int>, labels: List<Int>) {
        GalaxyConfigurationChoice.add(context, parent, key, title, values, labels)
    }
}
