package com.shilapi.xcertplay.network

/** L7 已出画面的信道策略；IP 地址模式由用户的车型配置决定。 */
object L7WifiDirectPolicy {
    // 固定信道仍受用户所选地区约束，不能越过地区许可或修改系统国家码。
    fun fixedChannel(model: String, region: GalaxyP2pRegion): Int? =
        149.takeIf { model == "l7" && region.policy.allows(it) }
}
