package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList
import com.shilapi.xcertplay.iap2.wire.Iap2ProtocolException

/** 手机原始引导语义；距离为米、剩余时间为秒，到达时间为 Unix 秒。 */
data class CarPlayNavigationSnapshot(
    val active: Boolean = false,
    val road: String? = null,
    val currentRoad: String? = null,
    val maneuverType: Int? = null,
    val drivingSide: Int = 0,
    val segmentRemainingMeters: Long? = null,
    val routeRemainingMeters: Long? = null,
    val routeRemainingSeconds: Long? = null,
    val arrivalEpochSeconds: Long? = null,
    val nextRoad: String? = road,
)

/** 解析手机字段并保留未知值；不把缺少距离当成零米，不借用其他车型输出。 */
class CarPlayNavigationInput(
    private val report: (String) -> Unit = {},
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private data class Maneuver(val type: Int? = null, val side: Int = 0, val road: String? = null)
    private val maneuvers = linkedMapOf<Int, Maneuver>()
    private var active = false
    private var index: Int? = null
    private var road: String? = null
    private var segment: Long? = null
    private var remainingMeters: Long? = null
    private var remainingSeconds: Long? = null
    private var arrival: Long? = null
    private var updated: Long? = null
    private var emptySince: Long? = null
    private var frames = 0L
    private var lastShape: String? = null
    private var lastReport = Long.MIN_VALUE

    @Synchronized fun accept(frame: Iap2Frame): CarPlayNavigationSnapshot? {
        if (frame.messageId != 0x5201 && frame.messageId != 0x5202) return null
        val fields = try { Iap2ParameterList.parse(frame.payload) } catch (_: Iap2ProtocolException) {
            emit("NavigationInputFields: message=${frame.messageId.toString(16)} malformed=true")
            return snapshot()
        }
        val sizes = if (frame.messageId == 0x5201) mapOf(1 to 1, 5 to 8, 6 to 8, 7 to 4, 10 to 4)
            else mapOf(1 to 2, 3 to 1, 8 to 1)
        if (sizes.any { (id, size) -> fields.first(id)?.payload?.size?.let { it < size } == true } ||
            (frame.messageId == 0x5201 && fields.first(13)?.payload?.size?.rem(2) == 1)) {
            emit("NavigationInputFields: message=${frame.messageId.toString(16)} malformed=true")
            return snapshot()
        }
        // 过期路线先清理，避免新路线继续使用旧转向或旧距离。
        snapshot()
        frames++
        if (frame.messageId == 0x5201) updateRoute(fields) else updateManeuver(fields)
        return snapshot()
    }
    private fun updateRoute(fields: Iap2ParameterList) {
        val state = number(fields, 1, 1)?.toInt()
        val shape = "routeState=${state ?: "unknown"} roadField=${fields.has(3)} maneuverList=${fields.has(13)}"
        if (lastShape != shape) { lastShape = shape; emit("NavigationInputFields: message=5201 $shape") }
        if (state == 0 || state == 2) { reset(); return }
        if (!active && state == null && !fields.has(13)) return
        if (state != null) active = true
        fields.first(13)?.payload?.let { list ->
            if (list.isEmpty()) {
                if (emptySince == null) emptySince = nanoTime()
            } else {
                val next = unsigned(list, 2).toInt()
                if (next != index) segment = null
                index = next
                active = true
                emptySince = null
            }
        }
        if (active) updated = nanoTime()
        fields.first(3)?.let { road = text(it.payload) }
        number(fields, 10, 4)?.let { segment = it }
        number(fields, 7, 4)?.let { remainingMeters = it }
        number(fields, 6, 8)?.let { remainingSeconds = it }
        number(fields, 5, 8)?.let { arrival = it.takeIf { value -> value > 0 } }
    }
    private fun updateManeuver(fields: Iap2ParameterList) {
        val key = number(fields, 1, 2)?.toInt() ?: return
        val previous = maneuvers[key] ?: Maneuver()
        maneuvers[key] = previous.copy(
            type = number(fields, 3, 1)?.toInt() ?: previous.type,
            side = number(fields, 8, 1)?.toInt() ?: previous.side,
            road = fields.first(4)?.let { text(it.payload) } ?: previous.road,
        )
        if (maneuvers.size > 128) maneuvers.keys.firstOrNull { it != index }?.let(maneuvers::remove)
    }
    @Synchronized fun snapshot(): CarPlayNavigationSnapshot {
        val time = nanoTime()
        if (active && updated?.let { time - it >= 30_000_000_000L } == true) reset()
        val hidden = emptySince?.let { time - it >= 3_000_000_000L } == true
        val maneuver = if (hidden) null else maneuvers[index]
        val value = if (!active) CarPlayNavigationSnapshot() else CarPlayNavigationSnapshot(
            true, maneuver?.road?.takeIf(String::isNotBlank) ?: road, road, maneuver?.type,
            maneuver?.side ?: 0, segment.takeUnless { hidden }, remainingMeters, remainingSeconds, arrival, maneuver?.road,
        )
        val shape = "active=${if (active) 1 else 0} roadPresent=${!value.road.isNullOrBlank()} " +
            "maneuverPresent=${value.maneuverType != null} remainingTimePresent=${value.routeRemainingSeconds != null} " +
            "remainingDistancePresent=${value.routeRemainingMeters != null} arrivalPresent=${value.arrivalEpochSeconds != null}"
        if (lastReport == Long.MIN_VALUE || time - lastReport >= 5_000_000_000L || shape != lastSnapshotShape) {
            lastReport = time
            lastSnapshotShape = shape
            emit("NavigationInput: $shape frames=$frames mapping=CARPLAY_NATIVE")
        }
        return value
    }
    private var lastSnapshotShape: String? = null
    @Synchronized fun clear() { reset(); snapshot() }
    private fun reset() {
        active = false; index = null; road = null; segment = null
        remainingMeters = null; remainingSeconds = null; arrival = null
        updated = null; emptySince = null; maneuvers.clear()
    }
    private fun number(fields: Iap2ParameterList, id: Int, size: Int): Long? =
        fields.first(id)?.payload?.takeIf { it.size >= size }?.let { unsigned(it, size).takeIf { n -> n >= 0 } }
    private fun unsigned(bytes: ByteArray, size: Int): Long {
        var value = 0L
        repeat(size) { value = (value shl 8) or (bytes[it].toLong() and 255) }
        return value
    }
    private fun text(bytes: ByteArray): String = String(bytes.takeWhile { it != 0.toByte() }.toByteArray(), Charsets.UTF_8)
        .trim().take(256)
    private fun emit(line: String) { runCatching { report(line) } }
}
