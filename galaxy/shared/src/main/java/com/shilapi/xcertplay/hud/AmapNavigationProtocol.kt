package com.shilapi.xcertplay.hud

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 高德车机 10001／10019 的出站字段；不包含厂商 SDK、车辆信号或接收目标包。 */
object AmapNavigationProtocol {
    const val ACTION = "AUTONAVI_STANDARD_BROADCAST_SEND"
    const val STARTED = 8
    const val STOPPED = 9

    fun state(value: Int): Map<String, Any> = mapOf("KEY_TYPE" to 10019,
        "EXTRA_STATE" to value, "EXTRA_STATUS_DETAILS" to -1)

    fun guidance(value: CarPlayNavigationSnapshot): Map<String, Any> {
        val (icon, exit) = turn(value.maneuverType, value.drivingSide)
        return linkedMapOf<String, Any>(
            "KEY_TYPE" to 10001, "TYPE" to 0,
            "CUR_ROAD_NAME" to value.currentRoad.orEmpty().take(256),
            "NEXT_ROAD_NAME" to value.nextRoad.orEmpty().take(256),
            "NEW_ICON" to icon, "ICON" to icon, "ROUNG_ABOUT_NUM" to exit,
        ).apply {
            value.segmentRemainingMeters?.takeIf { it >= 0 }?.let { put("SEG_REMAIN_DIS", bounded(it)) }
            value.routeRemainingMeters?.takeIf { it >= 0 }?.let { put("ROUTE_REMAIN_DIS", bounded(it)) }
            value.routeRemainingSeconds?.takeIf { it >= 0 }?.let { put("ROUTE_REMAIN_TIME", bounded(it)) }
            value.arrivalEpochSeconds?.takeIf { it > 0 }?.let { seconds ->
                // 原始到达时刻不能当作剩余秒数，ETA_TEXT 仅提供本地时间展示。
                runCatching { DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
                    .format(Instant.ofEpochSecond(seconds)) }.getOrNull()?.let { put("ETA_TEXT", it) }
            }
        }
    }
    private fun bounded(value: Long): Int = value.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

    /** Apple 语义转换为高德 IconType；未知转向用 0 清除，禁止猜成直行。 */
    fun turn(type: Int?, drivingSide: Int): Pair<Int, Int> {
        val clockwise = drivingSide == 1
        if (type != null && type in 28..46) return (if (clockwise) 17 else 11) to (type - 27)
        val icon = when (type) {
            1, 20 -> 2
            2, 21 -> 3
            47 -> 6
            48 -> 7
            13, 22, 49, 52 -> 4
            14, 23, 50, 53 -> 5
            4, 18, 19, 26 -> if (clockwise) 19 else 8
            6 -> if (clockwise) 17 else 11
            7 -> if (clockwise) 18 else 12
            10, 12, 24, 25, 27 -> 15
            3, 5, 8, 9, 11, 51 -> 9
            else -> 0
        }
        return icon to 0
    }
}
