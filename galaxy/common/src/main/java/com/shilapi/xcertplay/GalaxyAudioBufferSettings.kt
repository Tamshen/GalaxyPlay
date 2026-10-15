package com.shilapi.xcertplay

import android.content.Context
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.AudioBufferSettings
import com.shilapi.xcertplay.media.MediaAudioBuffer

/** 缓冲选择沿用车型草稿；连接只读取一次，当前播放不受编辑影响。 */
internal object GalaxyAudioBufferSettings {
    const val PHONE = "galaxy_phone_buffer_ms"
    const val ASSISTANT = "galaxy_assistant_buffer_ms"
    const val NAVIGATION = "galaxy_navigation_buffer_ms"
    val defaults = listOf(PHONE, ASSISTANT, NAVIGATION).associateWith { AudioBufferSettings.SYSTEM_DEFAULT }
    private val realtimeLabels = listOf(R.string.audio_buffer_existing, R.string.audio_buffer_phone,
        R.string.audio_buffer_32, R.string.audio_buffer_80, R.string.audio_buffer_160,
        R.string.audio_buffer_300, R.string.audio_buffer_500)

    fun load(context: Context): AudioBufferSettings {
        val preferences = context.getSharedPreferences("xcertplay_airplay", 0)
        fun realtime(key: String) = AudioBufferSettings.sanitizeRealtime(preferences.getInt(key, AudioBufferSettings.SYSTEM_DEFAULT))
        return AudioBufferSettings(AirPlayPersistence.loadMediaBufferMillis(context), realtime(PHONE),
            realtime(ASSISTANT), realtime(NAVIGATION))
    }

    fun add(context: Context, parent: LinearLayout, includeMusic: Boolean) {
        L7SettingsSection.add(parent, context.getString(R.string.audio_buffer_title),
            description = context.getString(R.string.audio_buffer_hint)) { card ->
            if (includeMusic) choice(context, card, "media_buffer_ms", R.string.music_buffer,
                MediaAudioBuffer.presets, listOf(R.string.s_300_ms_default, R.string.s_500_ms,
                    R.string.s_1000_ms_most_stable, R.string.audio_buffer_phone), MediaAudioBuffer.DEFAULT_MILLIS)
            for ((key, title) in listOf(PHONE to R.string.audio_buffer_phone_title,
                ASSISTANT to R.string.audio_buffer_assistant_title, NAVIGATION to R.string.audio_buffer_navigation_title))
                choice(context, card, key, title, AudioBufferSettings.realtimeChoices, realtimeLabels, AudioBufferSettings.SYSTEM_DEFAULT)
        }
    }

    private fun choice(context: Context, card: LinearLayout, key: String, title: Int,
                       values: List<Int>, labels: List<Int>, default: Int) {
        val preferences = context.getSharedPreferences("xcertplay_airplay", 0)
        val names = labels.map(context::getString)
        fun current() = values.indexOf(preferences.getInt(key, default)).takeIf { it >= 0 } ?: values.indexOf(default)
        lateinit var row: L7SettingRow
        row = L7Components.valueRow(context, context.getString(title), names[current()]) {
            L7Components.select(context, context.getString(title), names, current(), context.getString(
                if (context is GalaxyConfigurationContext) R.string.profile_update_draft else R.string.save)) { selected ->
                preferences.edit().putInt(key, values[selected]).commit()
                row.setValue(names[selected])
            }
        }
        card.addView(row)
    }
}
