package com.shilapi.xcertplay.network

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/** Wi-Fi Direct 的临时 GO 广播补充；只追加已核对的元素，不覆盖系统或其他应用的列表。 */
object GalaxyP2pAutoJoin {
    const val FAILURE = "P2P_AUTO_JOIN_FAILED"
    private val lock = ReentrantLock()
    internal val element = "dd08${HotspotJoinElement.apple5Ghz.payload}"
    internal interface Shell : Closeable { fun run(command: String): String }

    fun prepare(info: WirelessHotspotInfo, cancelled: () -> Boolean, log: (String) -> Unit) {
        prepare(info, cancelled, log, { deadline -> GalaxyP2pLoopback.open(deadline, cancelled) }) {
            GalaxyP2pStabilization.await(cancelled, log)
        }
    }

    internal fun prepare(info: WirelessHotspotInfo, cancelled: () -> Boolean, log: (String) -> Unit,
                         connect: (Long) -> Shell, stabilize: () -> Unit) {
        if (ensure(info, cancelled, log, connect)) stabilize()
    }

    /** 接口尚未创建的固件仍在建组后准备；预写成功也必须再次核对实际组接口。 */
    fun prepareBeforeGroup(frequencyMHz: Int?, cancelled: () -> Boolean, log: (String) -> Unit) {
        if (frequencyMHz == null || frequencyMHz !in 5150..5895) return
        prepareBeforeGroup(cancelled, log) { deadline -> GalaxyP2pLoopback.open(deadline, cancelled) }
    }

    internal fun prepareBeforeGroup(cancelled: () -> Boolean, log: (String) -> Unit,
                                    connect: (Long) -> Shell) {
        try {
            val changed = ensureInterface(cancelled, { log("preGroup=true $it") }, connect)
            runCatching { log("P2P_PREPARE stage=BEFORE_GROUP_READY changed=$changed airBroadcast=UNVERIFIED") }
        } catch (failure: Exception) {
            if (cancelled() || Thread.currentThread().isInterrupted) throw failure
            runCatching { log("P2P_PREPARE stage=DEFERRED_TO_GROUP reason=PRECREATE_UNAVAILABLE_OR_REJECTED") }
        }
    }

    internal fun ensure(info: WirelessHotspotInfo, cancelled: () -> Boolean, log: (String) -> Unit,
                        connect: (Long) -> Shell): Boolean {
        if (info.backend != WirelessHotspotBackend.WIFI_P2P || info.frequencyMHz?.let { it in 5150..5895 } != true) return false
        if (info.interfaceName != "p2p0") throw WirelessStartupException(
            WirelessStartupFailure.HOTSPOT_CONFIGURATION, FAILURE)
        return ensureInterface(cancelled, log, connect)
    }

    private fun ensureInterface(cancelled: () -> Boolean, log: (String) -> Unit,
                                connect: (Long) -> Shell): Boolean {
        val started = System.nanoTime()
        val deadline = started + TimeUnit.SECONDS.toNanos(6)
        fun checkCurrent() {
            if (cancelled() || Thread.currentThread().isInterrupted || System.nanoTime() >= deadline)
                throw IOException("P2P_AUTO_JOIN_CANCELLED_OR_TIMEOUT")
        }
        var acquired = false
        fun event(body: String) { runCatching { log("p2pAutoJoin $body atNs=${System.nanoTime()} elapsedMs=${(System.nanoTime() - started) / 1_000_000}") } }
        try {
            while (!acquired) { checkCurrent(); acquired = lock.tryLock(50, TimeUnit.MILLISECONDS) }
            checkCurrent()
            event("stage=BEGIN")
            return connect(deadline).use { shell ->
                fun read(frame: Int): List<String> {
                    checkCurrent()
                    return elements(shell.run("/vendor/bin/wpa_cli -i p2p0 vendor_elem_get $frame"))
                        ?: throw IOException("P2P_AUTO_JOIN_INVALID_READBACK")
                }
                val before = listOf(2, 3).associateWith(::read)
                for ((frame, values) in before) event("stage=BEFORE frame=$frame targetPresent=${element in values} elementCount=${values.size} evidence=CONFIG_READBACK airBroadcast=UNVERIFIED")
                if (before.values.any { !compatible(it) }) throw IOException("P2P_AUTO_JOIN_CONFLICT")
                for ((frame, values) in before) {
                    checkCurrent()
                    if (element !in values) {
                        val reply = shell.run("/vendor/bin/wpa_cli -i p2p0 vendor_elem_add $frame $element")
                        if (reply.trim() != "OK") throw IOException("P2P_AUTO_JOIN_WRITE_REJECTED")
                        event("stage=ADD frame=$frame accepted=true")
                    }
                }
                for ((frame, values) in before) {
                    val after = read(frame)
                    event("stage=AFTER frame=$frame targetPresent=${element in after} elementCount=${after.size} evidence=CONFIG_READBACK airBroadcast=UNVERIFIED")
                    if (!compatible(after) || after.count { it == element } != 1 ||
                        values.groupingBy { it }.eachCount().any { (value, count) -> after.count { it == value } < count })
                        throw IOException("P2P_AUTO_JOIN_READBACK_MISMATCH")
                }
                checkCurrent()
                event("stage=VERIFIED frames=2,3 changed=${before.values.any { element !in it }}")
                before.values.any { element !in it }
            }
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            val reason = error.message?.takeIf { it.matches(Regex("P2P_AUTO_JOIN_[A-Z_]+")) } ?: "IO_ERROR"
            event("stage=FAILED reason=$reason exceptionType=${error.javaClass.simpleName}")
            throw WirelessStartupException(WirelessStartupFailure.HOTSPOT_CONFIGURATION, FAILURE)
        } finally { if (acquired) lock.unlock() }
    }

    private fun compatible(values: List<String>): Boolean {
        val apple = values.filter { it.startsWith("dd") && it.drop(4).startsWith("00a04000") }
        return apple.isEmpty() || apple == listOf(element)
    }

    internal fun elements(output: String): List<String>? {
        val hex = output.trim().lowercase()
        if (hex.length > 8192 || hex.length % 2 != 0 || hex.any { it !in "0123456789abcdef" }) return null
        val values = mutableListOf<String>()
        var offset = 0
        while (offset < hex.length) {
            if (hex.length - offset < 4) return null
            val end = offset + 4 + hex.substring(offset + 2, offset + 4).toInt(16) * 2
            if (end > hex.length) return null
            values += hex.substring(offset, end)
            offset = end
        }
        return values
    }
}
