package com.shilapi.xcertplay

import android.content.Context

/** 新安装和旧偏好均默认原车蓝牙；显式回退仅影响下一次连接。 */
internal object GalaxyMusicPlayback {
    private const val KEY = "galaxy_local_music_enabled"
    fun localEnabled(context: Context): Boolean =
        context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).getBoolean(KEY, false)
    fun saveLocalEnabled(context: Context, enabled: Boolean) {
        check(context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit()
            .putBoolean(KEY, enabled).commit())
    }
}
