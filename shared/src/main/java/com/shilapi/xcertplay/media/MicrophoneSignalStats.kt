package com.shilapi.xcertplay.media

import kotlin.math.abs
import kotlin.math.sqrt

/** 只保留 PCM 能量摘要，不保存人声；跨 read 的半个样本也正确拼接。 */
class MicrophoneSignalStats {
    var samples = 0L
        private set
    var zeros = 0L
        private set
    var peak = 0
        private set
    private var squares = 0L
    private var pendingLowByte = -1

    fun add(pcm: ByteArray, count: Int) {
        require(count in 0..pcm.size)
        for (index in 0 until count) {
            val low = pendingLowByte
            if (low < 0) pendingLowByte = pcm[index].toInt() and 0xff
            else {
                val value = (low or (pcm[index].toInt() shl 8)).toShort().toInt()
                pendingLowByte = -1
                samples++
                if (value == 0) zeros++
                peak = maxOf(peak, abs(value))
                squares += value.toLong() * value
            }
        }
    }

    val rms: Int get() = if (samples == 0L) 0 else sqrt(squares.toDouble() / samples).toInt()
    val zeroPercent: Int get() = if (samples == 0L) 0 else (zeros * 100 / samples).toInt()

    fun resetWindow() {
        samples = 0; zeros = 0; peak = 0; squares = 0
        // 保留未拼完的低字节，窗口边界不能改变样本对齐。
    }
}
