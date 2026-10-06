package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.AudioStreamId
import java.util.Locale

/** 沿 AD 分类音乐；102 也承载其他用途，不能按类型整体静音。 */
object LocalMediaAudioPolicy {
    fun isMusic(id: AudioStreamId, format: AudioFormat): Boolean {
        val role = id.audioType.takeIf { it.isNotBlank() } ?: format.audioType
        val type = role.trim().lowercase(Locale.ROOT)
        return type == "media" || (id.type == 102 && (type.isEmpty() || type == "default"))
    }
    fun shouldRender(enabled: Boolean, id: AudioStreamId, format: AudioFormat): Boolean =
        enabled || !isMusic(id, format)
}
