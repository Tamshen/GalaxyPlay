package com.shilapi.xcertplay.media

import android.media.MediaFormat

/** 只计算本次协商格式；能力未知或基础格式不可用时不推定可以降级。 */
data class VideoSessionFormat(val hevc: Boolean, val fps: Int) {
    companion object {
        fun select(hevc: Boolean, fps: Int, softwareHevc: Boolean,
                   supported: (String, Int, Boolean) -> Boolean?): VideoSessionFormat {
            val requested = supported(if (hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC,
                fps, hevc && softwareHevc)
            return if (requested == false && (hevc || fps != 30) &&
                supported(MediaFormat.MIMETYPE_VIDEO_AVC, 30, false) == true) VideoSessionFormat(false, 30)
            else VideoSessionFormat(hevc, fps)
        }
    }
}
