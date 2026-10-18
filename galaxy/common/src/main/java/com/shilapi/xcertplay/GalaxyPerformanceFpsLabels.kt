package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.host.R
import java.util.Locale

/** 面板与详情共用状态含义，只有紧凑窗口缩短提示，未知不冒充 0／60。 */
internal object GalaxyPerformanceFpsLabels {
    fun ui(context: Context, sample: GalaxyPerformanceSample?, compact: Boolean = false): String {
        val id = when (sample?.uiState) {
            GalaxyUiFpsState.ACTIVE -> return number(sample.uiFps)
            GalaxyUiFpsState.STATIC -> if (compact) R.string.performance_ui_idle_short else R.string.performance_ui_idle
            GalaxyUiFpsState.BACKGROUND -> if (compact) R.string.performance_ui_background_short else R.string.performance_ui_background
            GalaxyUiFpsState.INCOMPLETE -> R.string.performance_frames_incomplete
            GalaxyUiFpsState.UNAVAILABLE -> R.string.performance_frames_unavailable
            else -> if (compact) R.string.performance_frames_wait_short else R.string.performance_ui_wait
        }
        return context.getString(id)
    }
    fun carPlay(context: Context, sample: GalaxyPerformanceSample?, compact: Boolean = false): String = when {
        sample?.input?.carPlayConnected != true -> context.getString(if (compact) R.string.performance_no_stream_short else R.string.performance_no_stream)
        sample.main?.presentedFps == null -> context.getString(if (compact) R.string.performance_frames_wait_short else R.string.performance_video_wait)
        else -> number(sample.main?.presentedFps)
    }
    private fun number(value: Double?) = value?.let { String.format(Locale.US, "%.1f", it) } ?: "--"
}
