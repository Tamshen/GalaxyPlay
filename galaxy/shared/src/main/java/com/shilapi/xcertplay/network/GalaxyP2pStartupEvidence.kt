package com.shilapi.xcertplay.network

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** 复用启动观察线程做有界只读采样；系统回读不代表空口广播或手机 DHCP 成功。 */
internal class GalaxyP2pStartupEvidence(
    private val cancelled: () -> Boolean,
    private val connect: (Long, () -> Boolean) -> GalaxyP2pAutoJoin.Shell = { deadline, stop ->
        GalaxyP2pLoopback.open(deadline, stop)
    },
    private val nowNs: () -> Long = System::nanoTime,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private var samples = 0
    private var last = ""
    private var lastAt: Long? = null

    fun sample(): String {
        if (closed.get() || cancelled()) return "P2P_LINK_EVIDENCE sampling=stopped"
        if (samples >= 4) return "P2P_LINK_EVIDENCE sampling=limit cached=true " +
            "sampleAgeMs=${lastAt?.let { (nowNs() - it).coerceAtLeast(0) / 1_000_000 } ?: -1} $last"
        samples++
        val started = nowNs()
        val stop = { closed.get() || cancelled() || Thread.currentThread().isInterrupted }
        val evidence = try {
            connect(started + 3_000_000_000L, stop).use { shell ->
                fun query(command: String): String? = try {
                    if (stop()) null else shell.run(command).takeIf { it.length <= 16_384 }
                } catch (_: Exception) { null }
                listOf(
                    elements("frame2", query("/vendor/bin/wpa_cli -i p2p0 vendor_elem_get 2")),
                    elements("frame3", query("/vendor/bin/wpa_cli -i p2p0 vendor_elem_get 3")),
                    stations(query("/vendor/bin/wpa_cli -i p2p0 all_sta")),
                    neighbors(query("ip -4 neigh show dev p2p0")),
                ).joinToString(" ")
            }
        } catch (_: Exception) { "readback=UNAVAILABLE" }
        if (stop()) return "P2P_LINK_EVIDENCE sampling=stopped"
        last = evidence
        lastAt = nowNs()
        return "P2P_LINK_EVIDENCE sample=$samples durationMs=${(nowNs() - started).coerceAtLeast(0) / 1_000_000} " +
            "scope=ALL_P2P0_CLIENTS airBroadcast=UNVERIFIED dhcpLease=NOT_OBSERVED $evidence"
    }

    override fun close() { closed.set(true) }

    companion object {
        private val mac = Regex("(?i)^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")
        private fun valid(value: String?): Boolean = value != null && value.length <= 16_384 &&
            !value.lineSequence().any { it.trim() == "FAIL" || it.trim().startsWith("FAIL-") } && !value.contains("UNKNOWN COMMAND", true) &&
            !value.contains("not found", true) && !value.contains("denied", true)

        internal fun elements(label: String, value: String?): String {
            val values = value?.let(GalaxyP2pAutoJoin::elements)
            return "$label=" + when {
                values == null -> "UNAVAILABLE"
                GalaxyP2pAutoJoin.element in values -> "PRESENT"
                else -> "ABSENT"
            }
        }

        internal fun stations(value: String?): String {
            if (!valid(value)) return "stations=UNAVAILABLE authorized=UNKNOWN"
            val lines = value!!.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
            val count = lines.count { mac.matches(it) }
            // 非空但没有站点头不能当作零客户端；固件格式不一致时保留未知。
            if (lines.isNotEmpty() && count == 0) return "stations=UNAVAILABLE authorized=UNKNOWN"
            val flags = lines.filter { it.startsWith("flags=") }
            val authorized = if (flags.size == count) flags.count { "[AUTHORIZED]" in it }.toString() else "UNKNOWN"
            return "stations=$count authorized=$authorized evidence=SUPPLICANT_READBACK"
        }

        internal fun neighbors(value: String?): String {
            if (!valid(value)) return "neighborsV4=UNAVAILABLE reachable=UNKNOWN"
            val lines = value!!.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
            val states = setOf("REACHABLE", "STALE", "DELAY", "PROBE", "FAILED", "INCOMPLETE", "PERMANENT", "NOARP")
            val tokens = lines.map { it.split(Regex("\\s+")) }
            val addressesValid = tokens.all { parts ->
                val octets = parts.first().split('.')
                octets.size == 4 && octets.all { it.toIntOrNull()?.let { value -> value in 0..255 } == true }
            }
            val parsed = tokens.map { it.lastOrNull() }
            if (!addressesValid || parsed.any { it !in states }) return "neighborsV4=UNAVAILABLE reachable=UNKNOWN"
            return "neighborsV4=${lines.size} reachable=${parsed.count { it == "REACHABLE" }} " +
                "stale=${parsed.count { it == "STALE" }} unresolved=${parsed.count { it == "FAILED" || it == "INCOMPLETE" }} " +
                "evidence=NEIGHBOR_TABLE_NOT_DHCP"
        }
    }
}
