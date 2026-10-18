package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.orchestration.WirelessHotspotMode

/** 固定 L7 已出画面的 Direct 路径；车型由宿主确认，不根据产品名推断。 */
object L7WifiDirectPolicy {
    fun enabled(model: String, wireless: Boolean, mode: WirelessHotspotMode): Boolean =
        model == "l7" && wireless && mode == WirelessHotspotMode.WIFI_P2P

    // 固定信道仍受用户所选地区约束，不能越过地区许可或修改系统国家码。
    fun fixedChannel(model: String, region: GalaxyP2pRegion): Int? =
        149.takeIf { model == "l7" && region.policy.allows(it) }
}
