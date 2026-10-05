package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.iap2.wire.Iap2Frame

/** 手机原始引导语义；厂商枚举和目标距离单位在各自适配层核对。 */
data class CarPlayNavigationSnapshot(val active: Boolean = false, val road: String? = null)

/** 复用既有 iAP2 解析与过期规则，仅提取 Apple 字段，不调用任何车辆输出。 */
class CarPlayNavigationInput(nanoTime: () -> Long = System::nanoTime) {
    private val route = BydHudRouteState(nanoTime)
    @Synchronized fun accept(frame: Iap2Frame): CarPlayNavigationSnapshot? {
        if (frame.messageId != 0x5201 && frame.messageId != 0x5202) return null
        route.accept(frame.messageId, frame.payload)
        return snapshot()
    }
    @Synchronized fun snapshot(): CarPlayNavigationSnapshot = route.currentApple()?.let {
        CarPlayNavigationSnapshot(true, it.road.takeIf(String::isNotBlank))
    } ?: CarPlayNavigationSnapshot()
    @Synchronized fun clear() { route.clear() }
}
