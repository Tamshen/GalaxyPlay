package com.shilapi.xcertplay.media

import java.io.ByteArrayOutputStream

/** 接受 hvcC 和 Annex B 两种初始化数据，统一输出 Android 要求的 VPS/SPS/PPS。 */
internal object HevcConfiguration {
    private val start = byteArrayOf(0, 0, 0, 1)

    fun csd(data: ByteArray): ByteArray {
        val units = if (prefix(data, 0) > 0) annexUnits(data) else recordUnits(data)
        val parameters = units.filter { type(it) in 32..34 }.sortedBy(::type)
        if (!(32..34).all { required -> parameters.any { type(it) == required } }) return ByteArray(0)
        return ByteArrayOutputStream().apply {
            parameters.forEach { write(start); write(it) }
        }.toByteArray()
    }

    private fun type(unit: ByteArray): Int =
        if (unit.size >= 2 && unit[0].toInt() and 128 == 0 && unit[1].toInt() and 7 != 0)
            (unit[0].toInt() ushr 1) and 63 else -1

    private fun recordUnits(data: ByteArray): List<ByteArray> {
        if (data.size < 23 || data[0] != 1.toByte()) return emptyList()
        var cursor = 23
        val units = mutableListOf<ByteArray>()
        repeat(data[22].toInt() and 255) {
            if (cursor + 3 > data.size) return emptyList()
            val declaredType = data[cursor++].toInt() and 63
            val count = u16(data, cursor)
            cursor += 2
            repeat(count) {
                if (cursor + 2 > data.size) return emptyList()
                val size = u16(data, cursor)
                cursor += 2
                if (size < 2 || size > data.size - cursor) return emptyList()
                val unit = data.copyOfRange(cursor, cursor + size)
                if (type(unit) != declaredType) return emptyList()
                units.add(unit)
                cursor += size
            }
        }
        return if (cursor == data.size) units else emptyList()
    }

    private fun annexUnits(data: ByteArray): List<ByteArray> {
        var cursor = 0
        val units = mutableListOf<ByteArray>()
        while (cursor < data.size) {
            val length = prefix(data, cursor)
            if (length == 0) return emptyList()
            val begin = cursor + length
            cursor = begin
            while (cursor < data.size && prefix(data, cursor) == 0) cursor++
            var end = cursor
            // Annex B 允许起始码前或结尾存在填充零。
            while (end > begin && data[end - 1] == 0.toByte()) end--
            if (end - begin < 2) return emptyList()
            units.add(data.copyOfRange(begin, end))
        }
        return units
    }

    private fun prefix(data: ByteArray, at: Int): Int {
        if (at + 3 > data.size || data[at] != 0.toByte() || data[at + 1] != 0.toByte()) return 0
        if (data[at + 2] == 1.toByte()) return 3
        return if (at + 4 <= data.size && data[at + 2] == 0.toByte() && data[at + 3] == 1.toByte()) 4 else 0
    }

    private fun u16(data: ByteArray, at: Int) =
        ((data[at].toInt() and 255) shl 8) or (data[at + 1].toInt() and 255)
}
