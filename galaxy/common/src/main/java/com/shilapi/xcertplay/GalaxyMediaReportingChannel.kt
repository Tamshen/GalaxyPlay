package com.shilapi.xcertplay

import android.content.Context

/** 渠道随车型偏好冻结；兼容模式只调整注册和媒体来源，仍使用 GalaxyPlay 身份。 */
internal object GalaxyMediaReportingChannel {
    const val KEY = "galaxy_media_reporting_channel"
    enum class Mode(val linkMediaSession: Boolean, val sdkPackages: List<String>, val acknowledgeSourceChange: Boolean) {
        CURRENT(true, emptyList(), false),
        NETEASE_COMPAT(false, listOf("com.netease.cloudmusic.iot", "com.flyme.auto.music"), true),
        QQ_COMPAT(false, listOf("com.flyme.auto.music", "com.netease.cloudmusic.iot"), true),
        LOCAL(false, listOf("com.geely.linkhmi", "com.flyme.auto.music", "com.netease.cloudmusic.iot"), true)
    }

    fun load(context: Context): Mode {
        val stored = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).getString(KEY, null)
        return Mode.entries.firstOrNull { it.name == stored } ?: Mode.CURRENT
    }
}
