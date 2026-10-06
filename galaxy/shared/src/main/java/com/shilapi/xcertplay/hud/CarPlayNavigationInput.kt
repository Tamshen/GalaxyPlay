package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.iap2.wire.Iap2Frame

/** 手机原始引导语义；厂商枚举和目标距离单位在各自适配层核对。 */
data class CarPlayNavigationSnapshot(val active: Boolean = false, val road: String? = null)

/** 复用既有 iAP2 解析与过期规则，仅提取 Apple 字段，不调用任何车辆输出。 */
class CarPlayNavigationInput(private val report: (String) -> Unit = {},
                             private val nanoTime: () -> Long = System::nanoTime) {
    private var lastFields: List<Long>? = null
    private var lastReport = Long.MIN_VALUE
    private var frames = 0L
    private val route = BydHudRouteState(nanoTime)
    @Synchronized fun accept(frame: Iap2Frame): CarPlayNavigationSnapshot? {
        if (frame.messageId != 0x5201 && frame.messageId != 0x5202) return null
        route.accept(frame.messageId, frame.payload)
        frames++
        return snapshot()
    }
    @Synchronized fun snapshot(): CarPlayNavigationSnapshot {
        val value = route.currentApple()
        // 只记录 Apple 输入字段是否存在；不泄露路名、到达时间，也不推定 OEM 单位或枚举。
        val fields = listOf(if (value != null) 1L else 0L, if (!value?.road.isNullOrBlank()) 1L else 0L,
            if (value?.remainingSeconds != null) 1L else 0L, if (value?.remainingMeters != null) 1L else 0L,
            if (value?.arrivalEpochSeconds != null) 1L else 0L)
        val now = nanoTime()
        if (lastFields != fields || now - lastReport >= 5_000_000_000L) {
            lastFields = fields; lastReport = now
            runCatching { report("NavigationInput: active=${fields[0]} roadPresent=${fields[1]} remainingTimePresent=${fields[2]} remainingDistancePresent=${fields[3]} arrivalPresent=${fields[4]} frames=$frames mapping=UNCONFIRMED") }
        }
        return value?.let { CarPlayNavigationSnapshot(true, it.road.takeIf(String::isNotBlank)) } ?: CarPlayNavigationSnapshot()
    }
    @Synchronized fun clear() { route.clear(); snapshot() }
}
