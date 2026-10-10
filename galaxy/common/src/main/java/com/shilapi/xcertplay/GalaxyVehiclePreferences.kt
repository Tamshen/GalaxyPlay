package com.shilapi.xcertplay

import android.content.Context

/** 只使用会话持有的车型快照；保存新草稿不改变当前方控和原车上报。 */
internal object GalaxyVehiclePreferences {
    fun steering(context: Context) = enabled(context, "galaxy_steering_enabled")
    fun mediaReporting(context: Context) = enabled(context, "galaxy_media_reporting_enabled")
    fun navigationReporting(context: Context) = enabled(context, "galaxy_navigation_reporting_enabled")
    private fun enabled(context: Context, key: String) = context.getSharedPreferences("xcertplay_airplay", 0)
        .getBoolean(key, true)
}
