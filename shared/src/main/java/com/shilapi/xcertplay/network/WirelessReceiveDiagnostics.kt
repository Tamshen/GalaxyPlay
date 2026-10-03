package com.shilapi.xcertplay.network

import java.io.File
import java.io.IOException

/**
 * 由无线观察线程采样可选内核接收计数，不占用媒体线程。
 * 系统可能禁止访问，失败的数据源每轮只尝试一次。
 * UDP 计数属于整机网络命名空间，不能当作本应用或某条 RTP 流的丢包量。
 */
internal class WirelessReceiveDiagnostics(
    interfaceName: String?,
    private val read: (String, Int) -> String = ::readBoundedDiagnosticFile,
    private val nowNs: () -> Long = System::nanoTime,
) {
    private val interfaceSource = CounterSource("ifaceRx") {
        if (interfaceName == null || !INTERFACE_NAME.matches(interfaceName)) throw IOException()
        linkedMapOf<String, Long>().apply {
            INTERFACE_COUNTERS.forEach { (file, label) ->
                put(label, counter(read("/sys/class/net/$interfaceName/statistics/$file", 32).trim()))
            }
        }
    }
    private val udp4Source = CounterSource("udp4") {
        parseUdp4(read("/proc/net/snmp", MAX_PROC_BYTES))
    }
    private val udp6Source = CounterSource("udp6") {
        parseUdp6(read("/proc/net/snmp6", MAX_PROC_BYTES))
    }
    private var previousSampleNs: Long? = null

    fun snapshot(): String {
        val now = nowNs()
        val elapsed = previousSampleNs?.let { ((now - it).coerceAtLeast(0)) / 1_000_000 }
        previousSampleNs = now
        val prefix = "receiveCounters windowMs=${elapsed ?: "baseline"} udpScope=device "
        // 各数据源单独成行，保留完整 Long 类型增量。
        return listOf(interfaceSource, udp4Source, udp6Source).joinToString("\n") {
            prefix + it.snapshot()
        }
    }

    private class CounterSource(private val label: String, private val sample: () -> Map<String, Long>) {
        private var previous: Map<String, Long>? = null
        private var unavailable: String? = null

        fun snapshot(): String {
            unavailable?.let { return "$label=unavailable failureClass=$it" }
            val current = try { sample() } catch (error: Exception) {
                val type = error.javaClass.simpleName
                unavailable = type
                return "$label=unavailable failureClass=$type"
            }
            val before = previous
            previous = current
            if (before == null) return "$label=baseline"
            // 接口重建或计数回绕不能被误报成负丢包数。
            if (current.keys != before.keys || current.any { (key, value) -> value < before.getValue(key) }) {
                return "$label=reset"
            }
            return "$label=sampled " + current.entries.joinToString(" ") { (key, value) ->
                "$label${key}Delta=${value - before.getValue(key)}"
            }
        }
    }

    companion object {
        private const val MAX_PROC_BYTES = 16 * 1024
        private val INTERFACE_NAME = Regex("[A-Za-z0-9_.-]{1,15}")
        private val INTERFACE_COUNTERS = linkedMapOf(
            "rx_packets" to "Packets", "rx_bytes" to "Bytes", "rx_dropped" to "Dropped",
            "rx_errors" to "Errors", "rx_missed_errors" to "MissedErrors",
        )
        private val UDP_COUNTERS = listOf("InDatagrams", "InErrors", "RcvbufErrors", "InCsumErrors")

        internal fun parseUdp4(text: String): Map<String, Long> {
            if (text.length > MAX_PROC_BYTES) throw IOException()
            val rows = text.lineSequence().filter { it.startsWith("Udp:") }.take(3).toList()
            if (rows.size != 2) throw IOException()
            val names = rows[0].trim().split(Regex("\\s+")).drop(1)
            val values = rows[1].trim().split(Regex("\\s+")).drop(1)
            if (names.size != values.size || names.distinct().size != names.size) throw IOException()
            return selectedUdpCounters(names.zip(values).toMap())
        }

        internal fun parseUdp6(text: String): Map<String, Long> {
            if (text.length > MAX_PROC_BYTES) throw IOException()
            val values = linkedMapOf<String, String>()
            text.lineSequence().filter { it.startsWith("Udp6") }.forEach { row ->
                val parts = row.trim().split(Regex("\\s+"))
                if (parts.size != 2 || values.put(parts[0].removePrefix("Udp6"), parts[1]) != null) {
                    throw IOException()
                }
            }
            return selectedUdpCounters(values)
        }

        private fun selectedUdpCounters(values: Map<String, String>): Map<String, Long> =
            linkedMapOf<String, Long>().apply {
                UDP_COUNTERS.forEach { key ->
                    val value = values[key]
                    // 较旧内核可能没有校验和专项计数。
                    if (value == null && key == "InCsumErrors") return@forEach
                    put(key, counter(value ?: throw IOException()))
                }
            }

        private fun counter(value: String): Long =
            value.toLongOrNull()?.takeIf { it >= 0 } ?: throw IOException()
    }
}

/** 限量读取指定诊断文件，不导出原始内容或读取异常文本。 */
internal fun readBoundedDiagnosticFile(path: String, maxBytes: Int): String = File(path).inputStream().use { input ->
    val bytes = ByteArray(maxBytes + 1)
    var count = 0
    while (count < bytes.size) {
        val read = input.read(bytes, count, bytes.size - count)
        if (read < 0) break
        if (read == 0) throw IOException()
        count += read
    }
    if (count > maxBytes) throw IOException()
    String(bytes, 0, count, Charsets.US_ASCII)
}
