package com.shilapi.xcertplay

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import java.lang.reflect.InvocationHandler

/** 原厂卡片读取完整播放信息；未取得手机数据或命令能力的字段明确保持不支持。 */
internal class GalaxyOemPlaybackSnapshot(
    context: Context,
    private val value: CarPlayNowPlaying,
    private val source: Int,
    private val uuid: String,
    private val valid: () -> Boolean,
    private val artwork: () -> android.net.Uri?,
    private val controlEnabled: Boolean,
) : InvocationHandler {
    private val app = context.applicationContext
    private val appIcon = "android.resource://${app.packageName}/${app.applicationInfo.icon}"
    private val open = PendingIntent.getActivity(app, 42,
        Intent(app, GalaxySettingsActivity::class.java).putExtra("page", "home")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    override fun invoke(proxy: Any?, method: java.lang.reflect.Method, args: Array<out Any?>?): Any? {
        if (!valid()) return when (method.returnType) {
            java.lang.Long.TYPE -> 0L
            java.lang.Integer.TYPE -> 0
            java.lang.Boolean.TYPE -> false
            else -> null
        }
        return when (method.name) {
            "getTitle" -> value.title
            "getArtist" -> value.artist
            "getAlbum" -> value.album
            "getDuration" -> value.durationMillis ?: 0L
            "getArtwork" -> artwork()
            "getSourceType" -> source
            "getPlaybackStatus" -> if (value.playing) 1 else 0
            "getPackageName" -> app.packageName
            "getAppName" -> app.getString(R.string.app_name)
            "getAppIcon" -> appIcon
            "getLaunchIntent", "getPlayerIntent" -> open
            "getUuid" -> uuid
            // 服务以 getVip()!=0 标记会员限制；SDK 默认 -1 不能沿用到本应用。
            "getVip", "getDisplayId" -> 0
            "getPlayingMediaListType", "getPlayingItemPositionInQueue", "getRadioMode" -> -1
            "isSupportVrCtrlPlayStatus" -> controlEnabled
            else -> false
        }
    }
}
