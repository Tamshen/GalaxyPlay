package com.shilapi.xcertplay.network

/** 宿主提供车载地区候选；射频是否许可仍由系统决定，不修改国家码。 */
class P2pChannelPolicy(channels: List<Int>) {
    val channels: List<Int> = channels.toList()

    init {
        require(this.channels.isNotEmpty() && this.channels.distinct().size == this.channels.size)
        require(this.channels.all { it != WifiP2pChannels.AUTO && WifiP2pChannels.isValid(it) })
    }

    fun allows(channel: Int): Boolean = channel in channels

    internal fun plan(station: Int?, remembered: P2pCreationRequest?, manual: Int): List<P2pCreationRequest> {
        if (manual != WifiP2pChannels.AUTO) {
            if (!allows(manual)) throw WirelessStartupException(WirelessStartupFailure.HOTSPOT_CONFIGURATION,
                "Wi-Fi Direct channel is outside the selected vehicle region")
            return listOf(P2pCreationRequest(P2pCreationMode.PREFERRED_CHANNEL, WifiP2pChannels.frequencyMhz(manual)))
        }
        val five = channels.filter { it >= 36 }
        val two = listOf(6, 1, 11).filter(::allows)
        val stationChannel = station?.let(::wifiFrequencyMhzToChannel)?.takeIf(::allows)
        val rememberedChannel = remembered?.frequencyMHz?.let(::wifiFrequencyMhzToChannel)?.takeIf(::allows)
        val switchingFive = station != null && station in 5150..5895
        val rememberedSwitches = switchingFive && rememberedChannel != null && rememberedChannel >= 36 &&
            remembered?.frequencyMHz != station
        val ordered = buildList {
            if (rememberedChannel != null && !rememberedSwitches) add(rememberedChannel)
            if (stationChannel != null) add(stationChannel)
            // 共享 5 GHz 射频时，先用当前允许信道；跨信道跳频前先尝试 2.4 GHz。
            if (switchingFive || (stationChannel != null && stationChannel in 1..11)) { addAll(two); addAll(five) }
            else { addAll(five); addAll(two) }
            if (rememberedChannel != null && rememberedSwitches) add(rememberedChannel)
        }.distinct()
        return ordered.map { channel ->
            val frequency = WifiP2pChannels.frequencyMhz(channel)
            val mode = when {
                channel == stationChannel && channel >= 36 -> P2pCreationMode.ALIGNED_5_GHZ
                channel == stationChannel -> P2pCreationMode.ALIGNED_2_GHZ
                channel >= 36 -> P2pCreationMode.FIXED_5_GHZ
                else -> P2pCreationMode.FIXED_2_GHZ
            }
            P2pCreationRequest(mode, frequency)
        }
    }
}
