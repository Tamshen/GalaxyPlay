package com.shilapi.xcertplay

import android.media.MediaCodecList
import android.os.Bundle
import com.shilapi.xcertplay.host.R

internal enum class CodecProbeMethod(val title: Int) {
    JAVA_TYPE(R.string.codec_probe_java_type), JAVA_NAME(R.string.codec_probe_java_name),
    JAVA_ASYNC(R.string.codec_probe_java_async), NDK(R.string.codec_probe_ndk), JAVA_TUNED(R.string.codec_probe_java_tuned),
    OEM_UCAR(R.string.codec_probe_oem_ucar), OEM_UCAR_QTI(R.string.codec_probe_oem_ucar_qti),
    OEM_HISIGHT(R.string.codec_probe_oem_hisight), OEM_DMSDP_HDMI(R.string.codec_probe_oem_hdmi),
    OEM_DMSDP_CALL(R.string.codec_probe_oem_call), OEM_DMSDP_BUFFER(R.string.codec_probe_oem_buffer),
    OEM_LIBPAG(R.string.codec_probe_oem_libpag);

    val surfaceOutput get() = this != OEM_DMSDP_BUFFER
    val factory get() = ordinal >= OEM_UCAR.ordinal
    val description get() = when (this) {
        OEM_UCAR -> R.string.codec_probe_ucar_body
        OEM_UCAR_QTI -> R.string.codec_probe_ucar_qti_body
        OEM_HISIGHT -> R.string.codec_probe_hisight_body
        OEM_DMSDP_HDMI -> R.string.codec_probe_hdmi_body
        OEM_DMSDP_CALL -> R.string.codec_probe_call_body
        OEM_DMSDP_BUFFER -> R.string.codec_probe_buffer_body
        OEM_LIBPAG -> R.string.codec_probe_libpag_body
        else -> R.string.codec_probe_standard_body
    }
}
internal enum class CodecProbeVideo(val mime: String, val asset: String, val title: String) {
    AVC("video/avc", "codec-probe/avc.mp4", "H.264"), HEVC("video/hevc", "codec-probe/hevc.mp4", "HEVC")
}
internal enum class CodecProbeStage(val title: Int) {
    BIND(R.string.codec_stage_bind), SAMPLE(R.string.codec_stage_sample), CREATE(R.string.codec_stage_create),
    CONFIGURE(R.string.codec_stage_configure), START(R.string.codec_stage_start), FEED(R.string.codec_stage_feed),
    DRAIN(R.string.codec_stage_drain), RELEASE(R.string.codec_stage_release), DONE(R.string.codec_stage_done)
}
internal data class CodecProbeDecoder(val name: String, val hardware: Boolean, val software: Boolean) {
    companion object {
        fun list(video: CodecProbeVideo): List<CodecProbeDecoder> = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && it.supportedTypes.any { type -> type.equals(video.mime, true) } }
            .map { CodecProbeDecoder(it.name, android.os.Build.VERSION.SDK_INT >= 29 && it.isHardwareAccelerated, android.os.Build.VERSION.SDK_INT >= 29 && it.isSoftwareOnly) }
            .distinctBy { it.name }.sortedByDescending { it.hardware }
    }
}
internal data class CodecProbeResult(
    val run: Long, val method: CodecProbeMethod, val stage: CodecProbeStage,
    val name: String = "", val hardware: Boolean = false, val software: Boolean = false,
    val inputs: Int = 0, val outputs: Int = 0, val rendered: Int = -1,
    val eos: Boolean = false, val released: Boolean = false, val code: Int = 0,
    val reason: String = "", val elapsedMs: Long = 0, val processExited: Boolean = false,
    val video: CodecProbeVideo = CodecProbeVideo.AVC, val workerStarted: Boolean = true,
    val configInputs: Int = 0, val outputBytes: Long = 0,
) {
    val decoded get() = stage == CodecProbeStage.DONE && outputs > 0 && eos && released && code == 0 && reason.isEmpty()
    val hardwarePassed get() = decoded && hardware && !software
    fun bundle() = Bundle().apply {
        putLong("run", run); putInt("method", method.ordinal); putInt("stage", stage.ordinal); putInt("video", video.ordinal)
        putString("name", name.take(96)); putBoolean("hardware", hardware); putBoolean("software", software)
        putInt("inputs", inputs); putInt("outputs", outputs); putInt("rendered", rendered)
        putBoolean("eos", eos); putBoolean("released", released); putInt("code", code)
        putString("reason", reason.take(64)); putLong("elapsedMs", elapsedMs)
        putInt("configInputs", configInputs); putLong("outputBytes", outputBytes)
    }
    companion object {
        fun read(b: Bundle) = CodecProbeResult(b.getLong("run"), CodecProbeMethod.entries[b.getInt("method")],
            CodecProbeStage.entries[b.getInt("stage")], b.getString("name").orEmpty().take(96),
            b.getBoolean("hardware"), b.getBoolean("software"), b.getInt("inputs"), b.getInt("outputs"),
            b.getInt("rendered", -1), b.getBoolean("eos"), b.getBoolean("released"), b.getInt("code"),
            b.getString("reason").orEmpty().take(64), b.getLong("elapsedMs"), video = CodecProbeVideo.entries[b.getInt("video")],
            configInputs = b.getInt("configInputs"), outputBytes = b.getLong("outputBytes"))
    }
}

/** 子进程只回传摘要，由主进程进入既有日志队列，避免多个进程竞争日志文件。 */
internal object CodecProbeProtocol {
    const val HELLO = 1
    const val RUN = 2
    const val STAGE = 3
    const val RESULT = 4
    const val CONNECT = 5
}
