package com.shilapi.xcertplay.airplay

import kotlin.math.ceil

/** SETUP 的时间目标独立于设备容量；异常或超预算参数不进入播放端。 */
data class AudioBufferHint private constructor(
    val minimumMillis: Int,
    val maximumMillis: Int,
    val source: String,
) {
    companion object {
        const val MAX_MILLIS = 2000

        fun parse(stream: Map<String, Any?>, sampleRate: Int): AudioBufferHint? {
            integer(stream["audioLatencyMs"])?.takeIf { it in 1..MAX_MILLIS }?.let {
                return AudioBufferHint(it, it, "audioLatencyMs")
            }
            if (sampleRate <= 0) return null
            val minimum = integer(stream["latencyMin"]) ?: return null
            val maximum = if (stream.containsKey("latencyMax")) integer(stream["latencyMax"]) ?: return null else minimum
            if (minimum <= 0 || maximum < minimum || maximum.toLong() * 1000 > sampleRate.toLong() * MAX_MILLIS) return null
            return AudioBufferHint(ceil(minimum * 1000.0 / sampleRate).toInt(),
                ceil(maximum * 1000.0 / sampleRate).toInt(), "latencySamples")
        }

        private fun integer(value: Any?): Int? {
            val number = (value as? Number)?.toDouble() ?: return null
            return number.takeIf { it.isFinite() && it >= 0 && it <= Int.MAX_VALUE && it == kotlin.math.floor(it) }?.toInt()
        }
    }
}

internal fun AudioFormat.withBufferHint(stream: Map<String, Any?>): AudioFormat =
    copy(bufferHint = AudioBufferHint.parse(stream, sampleRate))
