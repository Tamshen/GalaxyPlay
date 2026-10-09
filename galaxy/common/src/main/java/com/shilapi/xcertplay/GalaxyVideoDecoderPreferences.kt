package com.shilapi.xcertplay

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaFormat

/** 车型与偏好在连接时读取；只查询能力，不创建解码器或改变正在播放的会话。 */
internal object GalaxyVideoDecoderPreferences {
    enum class Mode(val decoder: String?) {
        DEFAULT(null), C2("c2.qti.avc.decoder"), OMX("OMX.qcom.video.decoder.avc")
    }
    private const val PREFS = "xcertplay_airplay"

    fun supported(context: Context): Boolean {
        val model = L7AudioTemplates.model(context)
        val selected = context.getSharedPreferences("l7_audio_templates", Context.MODE_PRIVATE).contains("model")
        // 未识别且未手动选车型时不能把界面的旧 L7 默认值当作硬件识别结果。
        return model != L7AudioTemplates.Model.CUSTOM && (selected || L7AudioModelDetector.detect() == model)
    }

    fun availableHardwareDecoders(): Set<String> = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            !info.isEncoder && info.isHardwareAccelerated && !info.isSoftwareOnly &&
                info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) }
        }.map { it.name }.toSet()
    }.getOrDefault(emptySet())

    fun options(context: Context, available: Set<String>): List<Mode> =
        if (!supported(context)) listOf(Mode.DEFAULT)
        else Mode.entries.filter { it == Mode.DEFAULT || it.decoder in available }

    fun load(context: Context, available: Set<String> = availableHardwareDecoders()): Mode {
        val options = options(context, available)
        val key = key(context)
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, null)
        val requested = Mode.entries.firstOrNull { it.name == stored } ?: Mode.C2
        return requested.takeIf { it in options } ?: Mode.DEFAULT
    }

    fun save(context: Context, mode: Mode, expectedModel: L7AudioTemplates.Model) {
        check(L7AudioTemplates.model(context) == expectedModel)
        check(mode == Mode.DEFAULT || supported(context))
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(key(context), mode.name).commit())
    }

    private fun key(context: Context) = "galaxy_video_decoder_" + L7AudioTemplates.model(context).id
}
