package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.media.MediaPerformanceOptions

/** 独立应用偏好不随车型模板覆盖；会话入口读取后不再监听修改。 */
internal object GalaxyMediaPerformancePreferences {
    enum class Item(val key: String) {
        MUSIC("galaxy_performance_music"),
        VIDEO_BUFFER("galaxy_performance_video_buffer"),
        LOW_LATENCY("galaxy_performance_low_latency"),
    }
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("galaxy_media_performance", Context.MODE_PRIVATE)

    fun enabled(context: Context, item: Item): Boolean = prefs(context).getString(item.key, null) == "OEM"
    fun save(context: Context, item: Item, enabled: Boolean) {
        check(prefs(context).edit().putString(item.key, if (enabled) "OEM" else "DEFAULT").commit())
    }
    fun snapshot(context: Context) = MediaPerformanceOptions(
        enabled(context, Item.MUSIC), enabled(context, Item.VIDEO_BUFFER), enabled(context, Item.LOW_LATENCY))
}
