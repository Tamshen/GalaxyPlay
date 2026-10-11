package com.shilapi.xcertplay

import android.content.Context
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.AudioOutputPolicy
import com.shilapi.xcertplay.media.AudioOutputRole

/** 频道直接编辑；车型模板提供初始值，旧方案标识仅用于兼容读取。 */
internal object L7AudioSettings {
    fun page(context: Context, parent: LinearLayout,
             open: (String, Int, AudioOutputRole, (Int) -> Unit) -> Unit) {
        fun text(id: Int) = context.getString(id)
        val applyHint = if (context is GalaxyConfigurationContext) "" else text(R.string.l7_setting_apply_hint)
        fun refresh() { parent.removeAllViews(); page(context, parent, open) }
        L7SettingsSection.add(parent, text(R.string.l7_section_audio_routes),
            description = text(if (L7AudioTemplates.customInvalid(context)) R.string.l7_template_recovery
                else R.string.l7_audio_roles_note), footer = text(R.string.l7_audio_headrest_note)) { card ->
            add(context, card, custom = true, open = { title, current, role, apply ->
                open(title, current, role) { value -> apply(value); refresh() }
            })
            val routing = L7AudioTemplates.load(context)
            card.addView(L7Components.switchRow(context, text(R.string.l7_audio_bus),
                text(if (routing.hasOutputBuses) R.string.l7_template_bus_custom_note else R.string.l7_template_bus_unconfigured),
                routing.preferBus) {
                change(context) {
                    L7AudioTemplates.saveCustom(context, L7AudioTemplates.load(context).withBusEnabled(it))
                }
                refresh()
            }.apply { isEnabled = routing.hasOutputBuses })
        }
        L7SettingsSection.add(parent, text(R.string.galaxy_navigation_device)) { GalaxyNavigationOutput.add(context, it) }
        L7SettingsSection.add(parent, text(R.string.l7_section_audio_playback), footer = applyHint) { card ->
            val presets = com.shilapi.xcertplay.media.MediaAudioBuffer.presets
            if (context !is GalaxyConfigurationContext) L7Components.choice(card, text(R.string.music_buffer), listOf(text(R.string.s_300_ms_default),
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

        }
        L7SettingsSection.add(parent, text(R.string.l7_section_bluetooth_audio)) { L7BluetoothAudioSettings.add(context, it, showExclusive = context !is GalaxyConfigurationContext) }
    }

    fun modeName(mode: L7AudioTemplates.Mode): Int = when (mode) {
        L7AudioTemplates.Mode.L7 -> R.string.l7_template_l7
        L7AudioTemplates.Mode.L6 -> R.string.l7_template_l6
        L7AudioTemplates.Mode.SYSTEM -> R.string.l7_template_system
        L7AudioTemplates.Mode.BUS -> R.string.l7_template_bus
        L7AudioTemplates.Mode.CUSTOM -> R.string.l7_template_custom
    }

    fun add(context: Context, parent: LinearLayout,
            custom: Boolean = false, open: (String, Int, AudioOutputRole, (Int) -> Unit) -> Unit) {
        listOf(AudioOutputRole.MEDIA, AudioOutputRole.NAVIGATION, AudioOutputRole.ASSISTANT, AudioOutputRole.PHONE).forEach { role ->
            val title = context.getString(when (role) {
                AudioOutputRole.MEDIA -> R.string.l7_audio_media
                AudioOutputRole.ASSISTANT -> R.string.l7_audio_assistant
                AudioOutputRole.NAVIGATION -> R.string.l7_audio_navigation
                AudioOutputRole.PHONE -> R.string.l7_audio_phone
            })
            lateinit var row: L7SettingRow
            row = L7Components.valueRow(context, title, label(context, load(context, role, custom))) {
                open(title, load(context, role, custom), role) { value ->
                    change(context) {
                        if (custom) L7AudioTemplates.saveCustom(context, L7AudioTemplates.load(context).withChoice(role, value))
                        else save(context, role, value)
                        row.setValue(label(context, value))
                    }
                }
            }
            parent.addView(row)
        }
    }

    fun label(context: Context, value: Int): String = context.resources.getStringArray(R.array.l7_audio_stream_names)
        .get(AudioOutputPolicy.choices.indexOf(value).coerceAtLeast(0))

    private fun load(context: Context, role: AudioOutputRole, custom: Boolean): Int =
        if (custom) L7AudioTemplates.load(context).choice(role) else when (role) {
        AudioOutputRole.MEDIA -> AirPlayPersistence.loadMediaAudioChannel(context)
        AudioOutputRole.ASSISTANT -> AirPlayPersistence.loadAssistantAudioChannel(context)
        AudioOutputRole.NAVIGATION -> AirPlayPersistence.loadNavigationAudioChannel(context)
        AudioOutputRole.PHONE -> L7AudioTemplates.load(context).choice(role)
    }

    private fun change(context: Context, action: () -> Unit) {
        if (runCatching(action).isFailure)
            android.widget.Toast.makeText(context, R.string.l7_template_save_failed, android.widget.Toast.LENGTH_LONG).show()
    }

    private fun save(context: Context, role: AudioOutputRole, value: Int) = when (role) {
        AudioOutputRole.MEDIA -> AirPlayPersistence.saveMediaAudioChannel(context, value)
        AudioOutputRole.ASSISTANT -> AirPlayPersistence.saveAssistantAudioChannel(context, value)
        AudioOutputRole.NAVIGATION -> AirPlayPersistence.saveNavigationAudioChannel(context, value)
        AudioOutputRole.PHONE -> L7AudioTemplates.saveCustom(context, L7AudioTemplates.load(context).withChoice(role, value))
    }
}
