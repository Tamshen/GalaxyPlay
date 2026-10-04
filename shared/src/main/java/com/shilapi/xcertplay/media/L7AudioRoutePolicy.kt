package com.shilapi.xcertplay.media

/** 原厂 BUS 仅作为候选地址；设备 ID、方向和格式能力必须来自当前系统枚举。 */
internal data class L7AudioDevice(
    val id: Int,
    val address: String,
    val input: Boolean,
    val sampleRates: List<Int> = emptyList(),
    val channelCounts: List<Int> = emptyList(),
)

internal object L7AudioRoutePolicy {
    fun candidate(channel: AudioChannel, input: Boolean, sampleRate: Int): String? = when (channel) {
        AudioChannel.MEDIA -> if (input) null else "BUS00_MEDIA"
        AudioChannel.ASSISTANT -> if (input) "BUS20_CARPLAY_SIRI_UL" else null
        AudioChannel.PHONE -> {
            val band = when (sampleRate) {
                8_000 -> "NB"
                16_000 -> "WB"
                32_000 -> "SWB"
                48_000 -> "FB"
                // 不仅凭采样率猜测 FaceTime，未确认的格式交给系统策略。
                else -> null
            }
            val prefix = when (band) {
                "NB" -> if (input) "BUS07" else "BUS06"
                "WB" -> if (input) "BUS11" else "BUS10"
                "SWB" -> if (input) "BUS13" else "BUS12"
                "FB" -> if (input) "BUS15" else "BUS14"
                else -> null
            }
            if (prefix == null) null else "${prefix}_CARPLAY_TELE_${band}_${if (input) "UP" else "DL"}"
        }
        // 原厂集成分支没有给导航/Siri 输出填入通知 BUS，保持标准 usage 路由。
        AudioChannel.NAVIGATION, AudioChannel.RINGTONE -> null
    }

    fun select(devices: List<L7AudioDevice>, channel: AudioChannel, input: Boolean,
               sampleRate: Int, channels: Int): L7AudioDevice? {
        val address = candidate(channel, input, sampleRate) ?: return null
        val matches = devices.filter {
            it.input == input && it.id > 0 && it.address.trim().equals(address, ignoreCase = true) &&
                (it.sampleRates.isEmpty() || sampleRate in it.sampleRates) &&
                (it.channelCounts.isEmpty() || channels in it.channelCounts)
        }
        // 同地址出现多个实例时不猜测物理输出，交给系统。
        return matches.singleOrNull()
    }
}
