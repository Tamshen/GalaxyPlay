package com.shilapi.xcertplay

import android.content.Context

/** USB 与无线统一使用本地音乐；覆盖升级也不再读取旧蓝牙音乐偏好。 */
internal object GalaxyMusicPlayback {
    @Suppress("UNUSED_PARAMETER")
    fun localEnabled(context: Context): Boolean = true
}
