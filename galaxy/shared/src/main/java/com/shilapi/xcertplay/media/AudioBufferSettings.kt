package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AudioFormat

/** 一次连接冻结四类选择；手机目标只用于本流，不能覆盖其他用途或已保存的手动档。 */
data class AudioBufferSettings(
    val mediaMillis: Int = MediaAudioBuffer.DEFAULT_MILLIS,
    val phoneMillis: Int = SYSTEM_DEFAULT,
    val assistantMillis: Int = SYSTEM_DEFAULT,
    val navigationMillis: Int = SYSTEM_DEFAULT,
) {
    internal fun choice(channel: AudioChannel): Int = when (channel) {
        AudioChannel.MEDIA -> MediaAudioBuffer.sanitize(mediaMillis)
        AudioChannel.PHONE -> sanitizeRealtime(phoneMillis)
        AudioChannel.ASSISTANT -> sanitizeRealtime(assistantMillis)
        AudioChannel.NAVIGATION -> sanitizeRealtime(navigationMillis)
        AudioChannel.RINGTONE -> SYSTEM_DEFAULT
    }

    companion object {
        const val SYSTEM_DEFAULT = -1
        const val FOLLOW_PHONE = 0
        val realtimeChoices = listOf(SYSTEM_DEFAULT, FOLLOW_PHONE, 32, 80, 160, 300, 500)
        fun sanitizeRealtime(value: Int): Int = value.takeIf { it in realtimeChoices } ?: SYSTEM_DEFAULT
    }
}

internal data class StreamAudioBuffer(val targetMillis: Int, val source: String, val followsPhone: Boolean) {
    companion object {
        fun resolve(settings: AudioBufferSettings, channel: AudioChannel, format: AudioFormat,
                    wireless: Boolean): StreamAudioBuffer? {
            val choice = settings.choice(channel)
            if (choice == AudioBufferSettings.SYSTEM_DEFAULT) return null
            if (choice != AudioBufferSettings.FOLLOW_PHONE) return StreamAudioBuffer(choice, "manual", false)
            val hint = format.bufferHint
            val fallback = if (channel == AudioChannel.MEDIA) MediaAudioBuffer.DEFAULT_MILLIS else if (wireless) 80 else 32
            return StreamAudioBuffer(hint?.minimumMillis ?: fallback, hint?.source ?: "fallback_missing_or_invalid", true)
        }
    }
}
