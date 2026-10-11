package com.shilapi.xcertplay.media

import android.media.MediaCodecList
import android.media.MediaFormat

/** 上下行分别核对；系统编解码器拒绝后仍可使用已内置的软件后备。 */
object AudioCodecCapabilities {
    @Volatile private var decoderRejected = false
    @Volatile private var encoderRejected = false

    fun opusOutputAvailable(): Boolean = SoftwareOpusSupport.outputAvailable || (!decoderRejected && opusCandidates(false).isNotEmpty())
    fun opusInputAvailable(): Boolean = SoftwareOpusSupport.inputAvailable || (!encoderRejected && opusCandidates(true).isNotEmpty())
    fun rejectOpusDecoder() { decoderRejected = true }
    fun rejectOpusEncoder() { encoderRejected = true }

    fun opusCandidates(encoder: Boolean): List<String> = runCatching {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 48_000, 1)
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            info.isEncoder == encoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_AUDIO_OPUS, true) } &&
                runCatching { info.getCapabilitiesForType(MediaFormat.MIMETYPE_AUDIO_OPUS).isFormatSupported(format) }
                    .getOrDefault(false)
        }.map { it.name }.distinct().take(8)
    }.getOrDefault(emptyList())
}
