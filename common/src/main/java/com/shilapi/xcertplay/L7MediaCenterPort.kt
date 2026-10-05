package com.shilapi.xcertplay

import android.net.Uri
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import java.io.Closeable

/** 客户端 token 仅留在适配器；注册、源接受、焦点和显示结果独立。 */
internal interface L7MediaCenterPort : Closeable {
    fun initialize(ready: (Boolean) -> Unit, command: (Int) -> Boolean, focus: (String?) -> Unit, selected: (Int) -> Boolean)
    fun register(): Boolean
    fun sources(values: IntArray): Boolean
    fun currentSource()
    fun requestPlay(): Boolean
    fun focusClient(): String?
    /** 仅撤销本地回调与快照，不发远端 IPC，允许退出先于阻塞调用完成。 */
    fun invalidate()
    fun update(value: CarPlayNowPlaying, artwork: Uri?): Boolean
    fun progress(milliseconds: Long)
    fun unregister(): Boolean
    companion object {
        // 原厂 CarPlay 样本的媒体源枚举；L7 是否接受和实际控制对象另行检测。
        const val CARPLAY_SOURCE = 13
    }
}
