package com.shilapi.xcertplay

import android.content.Context
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.AudioOutputPolicy
import com.shilapi.xcertplay.media.AudioOutputRole

/** 三用途均独立保存；读取旧媒体/导航键，新增助手默认值不覆盖已有选择。 */
internal object L7AudioSettings {
    fun page(context: Context, parent: LinearLayout,
             open: (String, Int, AudioOutputRole, (Int) -> Unit) -> Unit) {
        fun text(id: Int) = context.getString(id)
        L7SettingsSection.add(parent, text(R.string.l7_section_audio_routes),
            description = text(R.string.l7_audio_roles_note), footer = text(R.string.l7_audio_headrest_note)) { card ->
            add(context, card, open)
            card.addView(L7SettingRow(context, text(R.string.l7_audio_phone), text(R.string.l7_audio_phone_note)).apply {
                setValue(text(R.string.l7_audio_phone_usage))
            })
            card.addView(L7Components.actionRow(context, text(R.string.l7_audio_restore),
                text(R.string.l7_audio_restore_hint)) {
                L7Dialogs.builder(context).setTitle(R.string.l7_audio_restore)
                    .setMessage(R.string.l7_audio_restore_confirm)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.l7_save_next_connection) { _, _ ->
                        AirPlayPersistence.restoreUsageAudioDefaults(context)
                        parent.removeAllViews()
                        page(context, parent, open)
                    }.show()
            })
        }
        L7SettingsSection.add(parent, text(R.string.l7_section_audio_playback), footer = text(R.string.l7_setting_apply_hint)) { card ->
            val presets = com.shilapi.xcertplay.media.MediaAudioBuffer.presets
            L7Components.choice(card, text(R.string.music_buffer), listOf(text(R.string.s_300_ms_default),
                text(R.string.s_500_ms), text(R.string.s_1000_ms_most_stable)),
                presets.indexOf(AirPlayPersistence.loadMediaBufferMillis(context))) {
                AirPlayPersistence.saveMediaBufferMillis(context, presets[it])
            }
            card.addView(L7Components.switchRow(context, text(R.string.contrib_audio_home_toggle_audio_focus),
                text(R.string.contrib_audio_home_toggle_audio_focus_desc), AirPlayPersistence.loadAudioFocusEnabled(context)) {
                AirPlayPersistence.saveAudioFocusEnabled(context, it)
            })
            card.addView(L7Components.switchRow(context, text(R.string.l7_call_processing),
                text(R.string.l7_call_processing_note), AirPlayPersistence.loadCallProcessingEnabled(context)) {
                AirPlayPersistence.saveCallProcessingEnabled(context, it)
            })
            card.addView(L7Components.switchRow(context, text(R.string.l7_audio_bus),
                text(R.string.l7_audio_bus_note), AirPlayPersistence.loadL7AudioBusEnabled(context)) {
                AirPlayPersistence.saveL7AudioBusEnabled(context, it)
            })
            if (context.resources.getBoolean(R.bool.config_advanced_audio_channel_mapping)) {
                card.addView(L7Components.switchRow(context, text(R.string.advanced_audio_channel_mapping),
                    text(R.string.use_usage_content_type_routing_instead_of_stream_type),
                    AirPlayPersistence.loadAdvancedAudioChannelMapping(context)) {
                    AirPlayPersistence.saveAdvancedAudioChannelMapping(context, it)
                })
            }
        }
        L7SettingsSection.add(parent, text(R.string.l7_section_bluetooth_audio)) { L7BluetoothAudioSettings.add(context, it) }
    }

    fun add(context: Context, parent: LinearLayout,
            open: (String, Int, AudioOutputRole, (Int) -> Unit) -> Unit) {
        listOf(AudioOutputRole.MEDIA, AudioOutputRole.NAVIGATION, AudioOutputRole.ASSISTANT).forEach { role ->
            val title = context.getString(when (role) {
                AudioOutputRole.MEDIA -> R.string.l7_audio_media
                AudioOutputRole.ASSISTANT -> R.string.l7_audio_assistant
                AudioOutputRole.NAVIGATION -> R.string.l7_audio_navigation
            })
            lateinit var row: L7SettingRow
            row = L7Components.valueRow(context, title, label(context, load(context, role))) {
                open(title, load(context, role), role) { value ->
                    save(context, role, value)
                    row.setValue(label(context, value))
                }
            }
            parent.addView(row)
        }
    }

    fun label(context: Context, value: Int): String = context.resources.getStringArray(R.array.l7_audio_stream_names)
        .get(AudioOutputPolicy.choices.indexOf(value).coerceAtLeast(0))

    private fun load(context: Context, role: AudioOutputRole): Int = when (role) {
        AudioOutputRole.MEDIA -> AirPlayPersistence.loadMediaAudioChannel(context)
        AudioOutputRole.ASSISTANT -> AirPlayPersistence.loadAssistantAudioChannel(context)
        AudioOutputRole.NAVIGATION -> AirPlayPersistence.loadNavigationAudioChannel(context)
    }

    private fun save(context: Context, role: AudioOutputRole, value: Int) = when (role) {
        AudioOutputRole.MEDIA -> AirPlayPersistence.saveMediaAudioChannel(context, value)
        AudioOutputRole.ASSISTANT -> AirPlayPersistence.saveAssistantAudioChannel(context, value)
        AudioOutputRole.NAVIGATION -> AirPlayPersistence.saveNavigationAudioChannel(context, value)
    }
}
