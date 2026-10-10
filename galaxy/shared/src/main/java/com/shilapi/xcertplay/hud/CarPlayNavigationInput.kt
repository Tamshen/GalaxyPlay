package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList
import com.shilapi.xcertplay.iap2.wire.Iap2ProtocolException

/** 手机原始引导语义；厂商枚举和目标距离单位在各自适配层核对。 */
data class CarPlayNavigationSnapshot(val active: Boolean = false, val road: String? = null)

/** 复用既有 iAP2 解析与过期规则，仅提取 Apple 字段，不调用任何车辆输出。 */
class CarPlayNavigationInput(private val report: (String) -> Unit = {},
                             private val nanoTime: () -> Long = System::nanoTime) {
    private var lastFields: List<Long>? = null
    private var lastReport = Long.MIN_VALUE
    private var frames = 0L
    private var lastInputShape: String? = null
    private val route = BydHudRouteState(nanoTime)
    @Synchronized fun accept(frame: Iap2Frame): CarPlayNavigationSnapshot? {
        if (frame.messageId != 0x5201 && frame.messageId != 0x5202) return null
        reportInputShape(frame)
        route.accept(frame.messageId, frame.payload)
        frames++
        return snapshot()
    }
    @Synchronized fun snapshot(): CarPlayNavigationSnapshot {
        val value = route.currentApple()
        val road = route.currentRouteRoad()
        // 只记录 Apple 输入字段是否存在；不泄露路名、到达时间，也不推定 OEM 单位或枚举。
        val fields = listOf(if (road != null) 1L else 0L, if (!road.isNullOrBlank()) 1L else 0L,
            if (value?.remainingSeconds != null) 1L else 0L, if (value?.remainingMeters != null) 1L else 0L,
            if (value?.arrivalEpochSeconds != null) 1L else 0L)
        val now = nanoTime()
        if (lastFields != fields || now - lastReport >= 5_000_000_000L) {
            lastFields = fields; lastReport = now
            runCatching { report("NavigationInput: active=${fields[0]} roadPresent=${fields[1]} remainingTimePresent=${fields[2]} remainingDistancePresent=${fields[3]} arrivalPresent=${fields[4]} frames=$frames mapping=UNCONFIRMED") }
        }
        return road?.let { CarPlayNavigationSnapshot(true, it.takeIf(String::isNotBlank)) } ?: CarPlayNavigationSnapshot()
    }
    @Synchronized fun clear() { route.clear(); snapshot() }

    /** 区分未导航、仅路名与缺少转向详情；只记录字段存在和状态编号，不记录原始载荷。 */
    private fun reportInputShape(frame: Iap2Frame) {
        val shape = try {
            val fields = Iap2ParameterList.parse(frame.payload)
            if (frame.messageId == 0x5201) {
                val state = fields.first(1)?.payload?.singleOrNull()?.toInt()?.and(255)
                "message=5201 routeState=${state ?: "unknown"} roadField=${fields.has(3)} maneuverList=${fields.has(13)}"
            } else "message=5202 indexField=${fields.has(1)} typeField=${fields.has(3)} roadField=${fields.has(4)}"
        } catch (_: Iap2ProtocolException) { "message=${frame.messageId.toString(16)} malformed=true" }
        if (lastInputShape == shape) return
        lastInputShape = shape
        runCatching { report("NavigationInputFields: $shape") }
    }
}
