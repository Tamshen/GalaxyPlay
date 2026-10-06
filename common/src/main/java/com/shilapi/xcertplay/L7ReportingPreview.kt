package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.host.R

/** 展示测试组件写出的固定字段；车机是否显示由用户观察，不从按钮点击猜测内容。 */
internal data class L7ReportingPreview(val track: String? = null, val playing: Boolean = false,
    val elapsedMs: Long = 0, val cover: Int? = null, val road: String? = null) {
    fun text(context: Context): String {
        if (road != null) return context.getString(R.string.l7_report_expected_road,
            context.getString(if (road == "A") R.string.l7_report_road_a else R.string.l7_report_road_b))
        return context.getString(R.string.l7_report_expected_media,
            context.getString(if (track == "A") R.string.l7_report_track_a else R.string.l7_report_track_b),
            context.getString(if (playing) R.string.l7_report_playing else R.string.l7_report_paused),
            elapsedMs / 1000,
            context.getString(when (cover) { 1 -> R.string.l7_report_green; 2 -> R.string.l7_report_blue; else -> R.string.l7_report_no_cover }))
    }
    companion object {
        fun parse(kind: L7ReportingKind, line: String): L7ReportingPreview? {
            if (kind == L7ReportingKind.MEDIA && line.startsWith("stage=androidMedia ")) {
                val match = Regex("track=([AB]) playing=(true|false) elapsedMs=(\\d+) cover=(1|2|none)").find(line) ?: return null
                val elapsed = match.groupValues[3].toLongOrNull()?.takeIf { it in 0..180_000 } ?: return null
                return L7ReportingPreview(match.groupValues[1], match.groupValues[2] == "true", elapsed,
                    match.groupValues[4].toIntOrNull())
            }
            if (kind == L7ReportingKind.NAVIGATION && line.startsWith("stage=fixture ")) {
                val road = Regex("road=([AB]) active=true").find(line)?.groupValues?.get(1) ?: return null
                return L7ReportingPreview(road = road)
            }
            return null
        }
    }
}
