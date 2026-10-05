package com.shilapi.xcertplay

import android.content.Context
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.AudioOutputPolicy
import com.shilapi.xcertplay.media.AudioOutputRole

/** 内置方案只显示日常设置；自定义方案才展开配置文件与三用途调试。 */
internal object L7AudioSettings {
    fun page(context: Context, parent: LinearLayout,
             onImport: (() -> Unit)? = null, onExport: (() -> Unit)? = null,
             open: (String, Int, AudioOutputRole, (Int) -> Unit) -> Unit) {
        fun text(id: Int) = context.getString(id)
        val mode = L7AudioTemplates.mode(context)
        val custom = mode == L7AudioTemplates.Mode.CUSTOM
        fun refresh() { parent.removeAllViews(); page(context, parent, onImport, onExport, open) }
        val model = L7AudioTemplates.model(context)
        val modelNames = listOf(text(R.string.l7_template_model_l7), text(R.string.l7_template_model_l6))
        val modes = L7AudioTemplates.modes(context)
        val names = modes.map { text(modeName(it)) }
        val summary = when (mode) {
            L7AudioTemplates.Mode.L7 -> R.string.l7_template_l7_note
            L7AudioTemplates.Mode.L6 -> R.string.l7_template_l6_note
            L7AudioTemplates.Mode.BUS -> R.string.l7_template_bus_note
            L7AudioTemplates.Mode.CUSTOM -> if (L7AudioTemplates.customInvalid(context))
                R.string.l7_template_recovery else R.string.l7_template_custom_note
        }
        L7SettingsSection.add(parent, text(R.string.l7_template_title),
            description = context.getString(summary, modelNames[model.ordinal]), footer = text(R.string.l7_setting_apply_hint)) { card ->
            card.addView(L7Components.valueRow(context, text(R.string.l7_template_model), modelNames[model.ordinal]) {
                L7Components.select(context, text(R.string.l7_template_model), modelNames, model.ordinal,
                    text(R.string.l7_save_next_connection)) { selected ->
                    change(context) { L7AudioTemplates.selectModel(context, L7AudioTemplates.Model.entries[selected]); refresh() }
                }
            })
            card.addView(L7Components.valueRow(context, text(R.string.l7_template_select), names[modes.indexOf(mode)]) {
                L7Components.select(context, text(R.string.l7_template_select), names, modes.indexOf(mode),
                    text(R.string.l7_save_next_connection)) { selected ->
                    change(context) { L7AudioTemplates.select(context, modes[selected]); refresh() }
                }
            })
            if (custom) {
                card.addView(L7Components.actionRow(context, text(R.string.l7_template_edit),
                    text(R.string.l7_template_file_note)) { L7AudioTemplateEditor.show(context, ::refresh) })
                onImport?.let { card.addView(L7Components.actionRow(context, text(R.string.l7_template_import),
                    text(R.string.l7_template_import_note), click = it)) }
                onExport?.let { card.addView(L7Components.actionRow(context, text(R.string.l7_template_export),
                    text(R.string.l7_template_export_note), click = it)) }
            }
            card.addView(L7Components.actionRow(context, text(R.string.l7_audio_restore),
                context.getString(R.string.l7_template_restore_note, modelNames[model.ordinal])) {
                L7Dialogs.builder(context).setTitle(R.string.l7_audio_restore)
                    .setMessage(context.getString(R.string.l7_template_restore_confirm, modelNames[model.ordinal]))
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.l7_save_next_connection) { _, _ ->
                        change(context) {
                            AirPlayPersistence.restoreUsageAudioDefaults(context)
                            L7AudioTemplates.select(context, L7AudioTemplates.defaultMode(context))
                            refresh()
                        }
                    }.show()
            })
        }
        if (custom) L7SettingsSection.add(parent, text(R.string.l7_section_audio_routes),
            description = text(R.string.l7_audio_roles_note), footer = text(R.string.l7_audio_headrest_note)) { card ->
            add(context, card, custom = true, open = open)
            card.addView(L7SettingRow(context, text(R.string.l7_audio_phone), text(R.string.l7_audio_phone_note)).apply {
                setValue(text(R.string.l7_audio_phone_usage))
            })
            card.addView(L7Components.switchRow(context, text(R.string.l7_audio_bus),
                text(R.string.l7_template_bus_custom_note), L7AudioTemplates.load(context).preferBus) {
                change(context) {
                    L7AudioTemplates.saveCustom(context, L7AudioTemplates.load(context).withBusEnabled(it))
                }
                refresh()
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

        }
        L7SettingsSection.add(parent, text(R.string.l7_section_bluetooth_audio)) { L7BluetoothAudioSettings.add(context, it) }
    }

    private fun modeName(mode: L7AudioTemplates.Mode): Int = when (mode) {
        L7AudioTemplates.Mode.L7 -> R.string.l7_template_l7
        L7AudioTemplates.Mode.L6 -> R.string.l7_template_l6
        L7AudioTemplates.Mode.BUS -> R.string.l7_template_bus
        L7AudioTemplates.Mode.CUSTOM -> R.string.l7_template_custom
    }

    fun add(context: Context, parent: LinearLayout,
            custom: Boolean = false, open: (String, Int, AudioOutputRole, (Int) -> Unit) -> Unit) {
        listOf(AudioOutputRole.MEDIA, AudioOutputRole.NAVIGATION, AudioOutputRole.ASSISTANT).forEach { role ->
            val title = context.getString(when (role) {
                AudioOutputRole.MEDIA -> R.string.l7_audio_media
                AudioOutputRole.ASSISTANT -> R.string.l7_audio_assistant
                AudioOutputRole.NAVIGATION -> R.string.l7_audio_navigation
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
    }

    private fun change(context: Context, action: () -> Unit) {
        if (runCatching(action).isFailure)
            android.widget.Toast.makeText(context, R.string.l7_template_save_failed, android.widget.Toast.LENGTH_LONG).show()
    }

    private fun save(context: Context, role: AudioOutputRole, value: Int) = when (role) {
        AudioOutputRole.MEDIA -> AirPlayPersistence.saveMediaAudioChannel(context, value)
        AudioOutputRole.ASSISTANT -> AirPlayPersistence.saveAssistantAudioChannel(context, value)
        AudioOutputRole.NAVIGATION -> AirPlayPersistence.saveNavigationAudioChannel(context, value)
    }
}
