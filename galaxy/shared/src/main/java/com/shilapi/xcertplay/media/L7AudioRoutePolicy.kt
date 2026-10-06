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
    fun knownBus(address: String): Boolean = address in setOf(
        "bus0_media_out", "bus1_navigation_out", "bus2_voice_command_out", "bus3_call_ring_out", "bus4_call_out",
    )

    fun candidate(channel: AudioChannel, input: Boolean, sampleRate: Int,
                  template: AudioRoutingTemplate? = null): String? = template?.bus(channel, input)

    fun select(devices: List<L7AudioDevice>, channel: AudioChannel, input: Boolean,
               sampleRate: Int, channels: Int, template: AudioRoutingTemplate? = null): L7AudioDevice? {
        val address = candidate(channel, input, sampleRate, template) ?: return null
        val matches = devices.filter {
            it.input == input && it.id > 0 && it.address.trim().equals(address, ignoreCase = true) &&
                (it.sampleRates.isEmpty() || sampleRate in it.sampleRates) &&
                (it.channelCounts.isEmpty() || channels in it.channelCounts)
        }
        // 同地址出现多个实例时不猜测物理输出，交给系统。
        return matches.singleOrNull()
    }
}
