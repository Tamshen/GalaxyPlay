package com.autolink.adapterbinder

import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.Parcelable

/** 原车公开方法签名的测试替身，不进入产品源集。 */
interface IDimMediaInteractioncService : IInterface {
    fun sendMessage(info: PlayMediaInfo, reserved: Int)
    abstract class Stub : Binder(), IDimMediaInteractioncService {
        init { attachInterface(this, IDimMediaInteractioncService::class.java.name) }
        override fun asBinder(): IBinder = this
        companion object {
            @JvmStatic fun asInterface(binder: IBinder): IDimMediaInteractioncService =
                binder.queryLocalInterface(IDimMediaInteractioncService::class.java.name) as IDimMediaInteractioncService
        }
    }
}

class PlayMediaInfo : Parcelable {
    var source_type = 0
    var uuid = ""
    var title = ""
    var artist = ""
    var album = ""
    var artworkUrl: Uri? = null
    var duration = 0L
    var current_progress = 0L
    var playback_status = 0
    override fun describeContents() = 0
    override fun writeToParcel(dest: Parcel, flags: Int) = error("本测试核对委托原车 Parcelable，不自行序列化")
}
