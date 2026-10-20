package com.shilapi.xcertplay.network

/** 车机侧网络证据；客户端列表不证明手机获得 IP 或已发现 AirPlay。 */
data class WirelessLinkReadiness(val condition: Condition, val reportedClients: Int? = null) {
    enum class Condition { UNKNOWN, READY, GROUP_MISSING, GROUP_CHANGED, INTERFACE_MISSING, ADDRESS_MISSING }
}

/** 两次同类明确故障才提前退出；未知、零客户端与没有 TCP 都不能单独触发重建。 */
internal class GalaxyWirelessReadiness {
    private var previous = WirelessLinkReadiness.Condition.UNKNOWN
    private var consecutive = 0
    @Volatile var clientObserved = false
        private set

    fun observe(value: WirelessLinkReadiness): Boolean {
        if ((value.reportedClients ?: 0) > 0) clientObserved = true
        val failed = value.condition !in setOf(WirelessLinkReadiness.Condition.UNKNOWN, WirelessLinkReadiness.Condition.READY)
        consecutive = if (!failed) 0 else if (previous == value.condition) consecutive + 1 else 1
        previous = value.condition
        return consecutive >= 2
    }
}
