package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AudioFormat

/** E5 无线实时下行：协议目标与设备缓冲分开，主音乐不套用电话／Siri 的数值。 */
internal object E5WirelessAudioBuffer {
    const val JITTER_MILLIS = 80
    const val DEVICE_MILLIS = 40

    fun applies(wireless: Boolean, format: AudioFormat): Boolean = wireless && format.payloadType != 102 &&
        format.audioType.lowercase() in setOf("default", "compatibility", "telephony", "speechrecognition", "alert")

    fun plan(sampleRate: Int, channels: Int, minBufferBytes: Int): MediaAudioBuffer.Plan {
        val frameBytes = channels.coerceIn(1, 2) * 2
        val capacity = (sampleRate.toLong() * frameBytes * DEVICE_MILLIS / 1000).toInt()
        // AudioTrack 的系统最小容量必须尊重；首个样本写入后启动，等待由协议目标负责。
        return MediaAudioBuffer.Plan(maxOf(capacity, minBufferBytes), frameBytes)
    }
}

/** 用 RTP 样本时钟吸收无线包突发，不为每个到达包重新累计等待。只由音频 worker 使用。 */
internal class WirelessAudioPlayoutClock(private val sampleRate: Int) {
    private var originSample: Long? = null
    private var originNs = 0L

    fun dueNs(sample: Int, receivedNs: Long): Long {
        val unsigned = sample.toLong() and 0xffff_ffffL
        val origin = originSample
        if (origin == null) return reset(unsigned, receivedNs)
        val delta = (unsigned - origin) and 0xffff_ffffL
        // 时间戳倒退或跨越不连续会话时重新建立时钟，不能等待一个无界的远期时间。
        if (delta >= 0x8000_0000L) return reset(unsigned, receivedNs)
        val due = originNs + delta * 1_000_000_000L / sampleRate
        if (due - receivedNs > MAX_FUTURE_NS) return reset(unsigned, receivedNs)
        return due
    }

    private fun reset(sample: Long, receivedNs: Long): Long {
        originSample = sample
        originNs = receivedNs + E5WirelessAudioBuffer.JITTER_MILLIS * 1_000_000L
        return originNs
    }

    private companion object { const val MAX_FUTURE_NS = 500_000_000L }
}
