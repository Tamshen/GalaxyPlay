package com.shilapi.xcertplay

import android.content.Context
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R

/** 日常选项先呈现，细节按模块展开；隐藏字段仍完整保存在同一份车型配置中。 */
internal object GalaxyConfigurationSections {
    fun add(context: Context, parent: LinearLayout, group: Int, advanced: Boolean,
            expand: () -> Unit, credentials: () -> Unit) {
        fun text(id: Int) = context.getString(id)
        fun details(description: Int, body: () -> Unit) {
            parent.addView(L7Components.actionRow(context,
                text(if (advanced) R.string.config_advanced_close else R.string.config_advanced_open), text(description), click = expand))
            if (advanced) body()
        }
        when (group) {
            0 -> {
                quickChoice(context, parent, "display_scale_percent", R.string.config_quality,
                    listOf(100, 80, 60), listOf(R.string.config_quality_clear, R.string.config_quality_balanced, R.string.config_quality_light))
                quickChoice(context, parent, "display_fps", R.string.config_smoothness,
                    listOf(30, 60), listOf(R.string.config_smoothness_standard, R.string.config_smoothness_high))
                quickChoice(context, parent, "media_buffer_ms", R.string.config_music_stability,
                    listOf(300, 500, 1000), listOf(R.string.config_music_fast, R.string.config_music_stable, R.string.config_music_safest))
                parent.addView(L7Components.switchRow(context, text(R.string.config_bluetooth_music),
                    text(R.string.config_bluetooth_hint), AirPlayPersistence.loadBluetoothMediaExclusive(context)) {
                    AirPlayPersistence.saveBluetoothMediaExclusive(context, it)
                })
                GalaxyProfileFieldsView.add(context, parent, R.string.automatic_connection, setOf("wireless_enabled"))
                details(R.string.config_common_details) {
                    GalaxyProfileFieldsView.add(context, parent, R.string.l7_section_projection,
                        setOf("display_scale_percent", "display_fps"))
                }
            }
            1 -> {
                GalaxyHotspotSettings.add(context, parent)
                GalaxyProfileFieldsView.add(context, parent, R.string.automatic_connection, setOf("auto_connect"))
                details(R.string.config_connection_details) {
                    GalaxyProfileFieldsView.add(context, parent, R.string.automatic_connection,
                        setOf("auto_start_on_boot", "location_reporting_enabled"))
                }
            }
            2 -> {
                details(R.string.config_audio_details) {}
                val audio = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                parent.addView(audio)
                L7AudioSettings.page(context, audio, advanced = advanced) { title, current, _, apply ->
                    val choices = com.shilapi.xcertplay.media.AudioOutputPolicy.choices
                    L7Components.select(context, title, choices.map { L7AudioSettings.label(context, it) },
                        choices.indexOf(current), text(R.string.profile_update_draft)) { apply(choices[it]) }
                }
            }
            3 -> {
                quickChoice(context, parent, "ui_scale_percent", R.string.config_icon_size,
                    listOf(100, 125, 150), listOf(R.string.config_size_default, R.string.config_size_large, R.string.config_size_larger))
                GalaxyProfileFieldsView.add(context, parent, R.string.l7_section_projection,
                    setOf("hide_top_bar", "hide_bottom_bar", "right_hand_drive"))
                details(R.string.config_video_details) {
                    GalaxyVideoDecoderSettings.add(context, parent)
                    GalaxyProfileFieldsView.add(context, parent, R.string.l7_section_projection,
                        setOf("hevc_enabled", "hevc_software_decoder", "safe_area_draw_outside", "adapt_pip_resolution",
                            "display_scale_percent", "ui_scale_percent", "display_fps"))
                }
            }
            else -> {
                parent.addView(L7Components.note(context, text(R.string.config_auth_default_hint)))
                parent.addView(L7Components.actionRow(context, text(R.string.config_page_credentials),
                    text(R.string.config_page_credentials_hint), click = credentials))
                details(R.string.config_auth_details) { GalaxyProfileFieldsView.add(context, parent, R.string.l7_auth_title) }
            }
        }
        parent.addView(L7Components.note(context, text(R.string.config_page_next_connection)))
    }

    private fun quickChoice(context: Context, parent: LinearLayout, key: String, title: Int,
                            values: List<Int>, labels: List<Int>) {
        val preferences = context.getSharedPreferences("xcertplay_airplay", 0)
        val current = preferences.getInt(key, values.first())
        val options = if (current in values) values else values + current
        val names = labels.map(context::getString) + if (current in values) emptyList()
            else listOf(context.getString(R.string.config_custom_value, current))
        L7Components.choice(parent, context.getString(title), names, options.indexOf(current), context = context) { index ->
            preferences.edit().putInt(key, options[index]).commit()
        }
    }
}
