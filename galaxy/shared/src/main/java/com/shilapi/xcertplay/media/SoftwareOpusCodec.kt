package com.shilapi.xcertplay.media

import org.concentus.OpusApplication
import org.concentus.OpusDecoder
import org.concentus.OpusEncoder
import java.io.Closeable

/** 软件后备不依赖车机 MediaCodec；每个流独占状态与有界缓冲，不保留语音。 */
object SoftwareOpusSupport {
    val outputAvailable: Boolean by lazy {
        runCatching { SoftwareOpusDecoder(48_000, 1).close(); true }.getOrDefault(false)
    }
    val inputAvailable: Boolean by lazy {
        runCatching { SoftwareOpusEncoder(24_000).close(); true }.getOrDefault(false)
    }
}

internal class SoftwareOpusDecoder(sampleRate: Int, private val channels: Int) : Closeable {
    init { require(sampleRate == 48_000 && channels in 1..2) }
    private var decoder: OpusDecoder? = OpusDecoder(sampleRate, channels)
    // Opus 单包最长 120 ms；不按网络包长度申请 PCM 缓冲。
    private val samples = ShortArray(5_760 * channels)
    val pcm = ByteArray(samples.size * 2)

    fun decode(packet: ByteArray): Int {
        val active = checkNotNull(decoder) { "解码器已关闭" }
        require(packet.isNotEmpty() && packet.size <= 7_650)
        val frames = active.decode(packet, 0, packet.size, samples, 0, 5_760, false)
        check(frames in 1..5_760)
        repeat(frames * channels) { index ->
            val value = samples[index].toInt()
            pcm[index * 2] = value.toByte()
            pcm[index * 2 + 1] = (value shr 8).toByte()
        }
        return frames * channels * 2
    }

    override fun close() {
        decoder = null
        samples.fill(0)
        pcm.fill(0)
    }
}

/** 麦克风上行沿用 48 kHz、单声道、20 ms 的既有分帧契约。 */
internal class SoftwareOpusEncoder(bitrate: Int) : Closeable {
    private var encoder: OpusEncoder? = OpusEncoder(48_000, 1, OpusApplication.OPUS_APPLICATION_VOIP)
        .apply { setBitrate(bitrate) }
    private val samples = ShortArray(960)
    private val packet = ByteArray(1_275)
    val available: Boolean get() = encoder != null

    fun encode(pcm: ByteArray): List<ByteArray> {
        val active = encoder ?: return emptyList()
        require(pcm.size == samples.size * 2)
        samples.indices.forEach { index ->
            samples[index] = ((pcm[index * 2].toInt() and 255) or
                (pcm[index * 2 + 1].toInt() shl 8)).toShort()
        }
        val size = active.encode(samples, 0, samples.size, packet, 0, packet.size)
        check(size in 1..packet.size)
        return listOf(packet.copyOf(size))
    }

    override fun close() {
        encoder = null
        samples.fill(0)
        packet.fill(0)
    }
}
