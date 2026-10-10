package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.media.MediaRecorder
import org.json.JSONObject
import java.io.File

/** 音频配置读取参考 carlito12345/DiPlay v0.2.11；仅接入 L7，不读取身份与图标配置。 */
class L7FactoryAudioProfile internal constructor(
    private val attrs: JSONObject?,
    private val framework: (Class<*>, String) -> Int? = { type, name ->
        runCatching { type.getField(name).getInt(null) }.getOrNull()?.takeIf { it > 0 }
    },
) : PlatformAudioProfile {
    internal fun usage(channel: AudioChannel): Int {
        val kind = when (channel) {
            AudioChannel.MEDIA -> "MEDIA"
            AudioChannel.PHONE -> "PHONE"
            AudioChannel.ASSISTANT -> "SIRI"
            AudioChannel.NAVIGATION -> "GUIDANCE"
            AudioChannel.RINGTONE -> "RING"
        }
        val fallback = AudioOutputPolicy.usage(channel, 0)
        val name = "AUDIO_USAGE_CP_$kind"
        framework(AudioAttributes::class.java, name)?.let { return it }
        // 部分固件模板把全部用途填为 MEDIA；语音、电话和导航不能据此合并为音乐。
        return attrs?.optJSONObject("AudioUsage")?.optInt(name, -1)
            ?.takeIf { it > 0 && (channel == AudioChannel.MEDIA || it != AudioAttributes.USAGE_MEDIA) } ?: fallback
    }

    fun attributes(role: AudioOutputRole, choice: Int): AudioAttributes =
        attributes(role.channel, role.contentType, choice)

    override fun attributes(channel: AudioChannel, contentType: Int, choice: Int): AudioAttributes {
        val fallback = AudioOutputPolicy.usage(channel, choice)
        // 电话的旧默认保持标准通信；显式路由选择与其它用途走同一输出策略。
        val usage = if (channel != AudioChannel.PHONE &&
            (choice == AudioOutputPolicy.BUILTIN || AudioOutputPolicy.isLegacy(choice))) usage(channel) else fallback
        val result = runCatching {
            AudioAttributes.Builder().setUsage(usage).setContentType(contentType).build()
        }.getOrNull()
        // 与博越实现一致：系统不接受厂商 usage 时回退标准用途，不猜测私有数值。
        return if (result?.usage == usage) result else AudioAttributes.Builder()
            .setUsage(fallback).setContentType(contentType).build()
    }

    override fun microphoneSource(audioType: String, sampleRate: Int, wireless: Boolean): Int? {
        val kind = when (audioType.lowercase()) {
            "speechrecognition" -> "SIRI"
            "telephony" -> when {
                sampleRate <= 8_000 -> "PHONE_NB"
                sampleRate <= 16_000 -> "PHONE_WB"
                sampleRate <= 24_000 -> "PHONE_SWB"
                else -> "PHONE_FB"
            }
            "facetime" -> "FACETIME"
            else -> return null
        }
        val name = "AUDIO_SOURCE_${if (wireless) "WIRELESS_" else ""}CP_$kind"
        return framework(MediaRecorder.AudioSource::class.java, name)
            ?: attrs?.optJSONObject("AudioSource")?.optInt(name, -1)?.takeIf { it > MediaRecorder.AudioSource.MIC }
    }

    companion object {
        private val profile by lazy {
            val attrs = runCatching {
                val file = File("/vendor/etc/carplay/carplay_config.json")
                // 固定路径与上限；只保留 AudioAttrs，不持久化或记录配置正文。
                file.inputStream().use { input ->
                    val bytes = ByteArray(65_537)
                    var size = 0
                    while (size < bytes.size) {
                        val count = input.read(bytes, size, bytes.size - size)
                        if (count < 0) break
                        size += count
                    }
                    if (size > 65_536) null else JSONObject(String(bytes, 0, size, Charsets.UTF_8)).optJSONObject("AudioAttrs")
                }
            }.getOrNull()
            L7FactoryAudioProfile(attrs)
        }
        fun load(): L7FactoryAudioProfile = profile
    }
}
