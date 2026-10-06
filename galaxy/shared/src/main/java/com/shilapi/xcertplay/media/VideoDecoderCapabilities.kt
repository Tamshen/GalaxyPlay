package com.shilapi.xcertplay.media

import android.media.MediaCodecList
import android.os.Build

/** 协商和播放共用能力查询；查询不创建 codec，不占用或改变 Surface。 */
object VideoDecoderCapabilities {
    fun query(mime: String, width: Int, height: Int, fps: Double? = null,
              report: (String) -> Unit = {}): List<VideoDecoderCandidate> {
        fun emit(message: String) { runCatching { report(message) } }
        return MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.mapNotNull { info ->
            if (info.isEncoder || !info.supportedTypes.any { it.equals(mime, true) }) return@mapNotNull null
            runCatching {
                val caps = info.getCapabilitiesForType(mime)
                val video = caps.videoCapabilities
                val size = video?.isSizeSupported(width, height) == true
                val rate = fps == null || size && video?.areSizeAndRateSupported(width, height, fps) == true
                emit("decoder capability codec=${info.name} mime=$mime requested=${width}x$height " +
                    "sizeSupported=$size rateSupported=$rate widths=${video?.supportedWidths} heights=${video?.supportedHeights} " +
                    "alignment=${video?.widthAlignment}x${video?.heightAlignment} fpsRange=${video?.supportedFrameRates}")
                if (!size || !rate) return@runCatching null
                val software = if (Build.VERSION.SDK_INT >= 29) info.isSoftwareOnly else
                    info.name.startsWith("OMX.google.") || info.name.startsWith("c2.android.")
                VideoDecoderCandidate(info.name, if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated else !software,
                    software, Build.VERSION.SDK_INT >= 30 && caps.isFeatureSupported("low-latency"))
            }.onFailure { emit("decoder capability query failed codec=${info.name} mime=$mime error=${it.javaClass.simpleName}") }.getOrNull()
        }
    }
}
