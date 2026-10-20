package com.shilapi.xcertplay.media

/** 仅由视频 worker 使用；返回有效长度，后续读取不得扫描旧帧留下的尾部。 */
class VideoAnnexBBuffer(private val capacityLimit: Int = 8 * 1024 * 1024) {
    var bytes = ByteArray(0)
        private set
    private var storage = ByteArray(0)

    fun convert(source: ByteArray): Int {
        bytes = source
        if (source.size >= 3 && source[0] == 0.toByte() && source[1] == 0.toByte() &&
            (source[2] == 1.toByte() ||
                (source.size >= 4 && source[2] == 0.toByte() && source[3] == 1.toByte()))) return source.size
        if (source.size > capacityLimit) return 0
        var cursor = 0
        while (cursor <= source.size - 4) {
            val length = length(source, cursor)
            cursor += 4
            if (length <= 0 || length > source.size - cursor) return 0
            cursor += length
        }
        if (cursor == 0 || cursor != source.size) return 0
        if (storage.size < source.size) {
            storage = ByteArray(maxOf(source.size, (storage.size * 2).coerceAtMost(capacityLimit)))
        }
        source.copyInto(storage)
        cursor = 0
        while (cursor < source.size) {
            val length = length(source, cursor)
            storage[cursor] = 0
            storage[cursor + 1] = 0
            storage[cursor + 2] = 0
            storage[cursor + 3] = 1
            cursor += 4 + length
        }
        bytes = storage
        return source.size
    }

    private fun length(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 255) shl 24) or
            ((source[offset + 1].toInt() and 255) shl 16) or
            ((source[offset + 2].toInt() and 255) shl 8) or
            (source[offset + 3].toInt() and 255)
}
