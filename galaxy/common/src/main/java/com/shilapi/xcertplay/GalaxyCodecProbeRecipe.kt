package com.shilapi.xcertplay

import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.nio.ByteBuffer

/** VIDEO-02：仅复现已核对的 Android 调用组合，不加载原厂 SDK、动画引擎或私有传输。 */
internal class GalaxyCodecProbeRecipe(val method: CodecProbeMethod) {
    val ucar = method == CodecProbeMethod.OEM_UCAR || method == CodecProbeMethod.OEM_UCAR_QTI
    val async = method == CodecProbeMethod.JAVA_ASYNC || ucar
    val byType = method == CodecProbeMethod.JAVA_TYPE || method.factory
    val avcOnly = method in listOf(CodecProbeMethod.OEM_DMSDP_HDMI, CodecProbeMethod.OEM_DMSDP_CALL, CodecProbeMethod.OEM_DMSDP_BUFFER)
    val inBandCsd = ucar || method == CodecProbeMethod.OEM_HISIGHT || method == CodecProbeMethod.OEM_DMSDP_HDMI || method == CodecProbeMethod.OEM_DMSDP_CALL
    val immediate = ucar || method == CodecProbeMethod.OEM_HISIGHT || method == CodecProbeMethod.OEM_DMSDP_HDMI || method == CodecProbeMethod.OEM_DMSDP_CALL || method == CodecProbeMethod.OEM_LIBPAG
    val waitUs = if (method == CodecProbeMethod.OEM_LIBPAG) 1000L else 10_000L
    val frameRate = when (method) {
        CodecProbeMethod.OEM_DMSDP_HDMI -> 33
        CodecProbeMethod.OEM_DMSDP_CALL, CodecProbeMethod.OEM_DMSDP_BUFFER -> 30
        else -> 0
    }
    val lowLatency = when (method) {
        CodecProbeMethod.OEM_DMSDP_CALL, CodecProbeMethod.OEM_DMSDP_BUFFER -> 2
        else -> 0
    }
    val logSummary get() = "origin=${if (method.factory) "OEM_EQUIVALENT" else "LOCAL"} selection=${if (byType) "MIME" else "NAME"} surfaceOutput=${method.surfaceOutput} async=$async csdMode=${if (inBandCsd) "INPUT_CONFIG" else "FORMAT"} sampleWidth=640 sampleHeight=360 sampleFps=30 formatFps=$frameRate oemLowLatencyPlanned=$lowLatency oemQtiKeysPlanned=${method == CodecProbeMethod.OEM_UCAR_QTI} oemOperatingRatePlanned=${if (ucar) 45 else 0} dequeueUs=$waitUs ptsMode=${if (ucar) "COUNTER_5_US" else "SAMPLE"} renderMode=${if (!method.surfaceOutput) "BUFFER" else if (ucar) "NOW" else if (immediate) "BOOLEAN" else "SCHEDULED"}"

    fun format(sample: GalaxyCodecProbeSample): MediaFormat {
        if (!method.factory) return MediaFormat(sample.format)
        val source = sample.format
        val format = MediaFormat.createVideoFormat(source.getString(MediaFormat.KEY_MIME)!!,
            source.getInteger(MediaFormat.KEY_WIDTH), source.getInteger(MediaFormat.KEY_HEIGHT))
        if (!inBandCsd) sample.csd.forEachIndexed { index, bytes -> format.setByteBuffer("csd-$index", ByteBuffer.wrap(bytes)) }
        if (frameRate != 0) format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
        if (method == CodecProbeMethod.OEM_HISIGHT) format.setInteger(MediaFormat.KEY_PRIORITY, 0)
        if (method == CodecProbeMethod.OEM_DMSDP_HDMI || method == CodecProbeMethod.OEM_DMSDP_CALL)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        if (method == CodecProbeMethod.OEM_DMSDP_BUFFER) {
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1_536_000)
        }
        // 原厂值 2 仅作为显式实验请求，接受配置不证明驱动启用了低延迟。
        if (lowLatency != 0) format.setInteger("low-latency", lowLatency)
        if (method == CodecProbeMethod.OEM_UCAR_QTI) {
            format.setInteger("vendor.qti-ext-dec-low-latency.enable", 1)
            format.setInteger("vendor.qti-ext-dec-picture-order.enable", 1)
        }
        return format
    }
    fun presentationTime(index: Int, sample: GalaxyCodecProbeSample) = if (ucar) index * 5L else sample.times[index]
    fun isQti(name: String) = name.startsWith("c2.qti.", true) || name.startsWith("OMX.qcom.", true)
}
