package com.shilapi.xcertplay

import android.content.Context
import android.net.Uri
import android.os.IBinder
import android.os.Parcelable
import com.shilapi.xcertplay.media.CarPlayNowPlaying

/** 使用车机自带的 AIDL 代理和 Parcelable，字段布局由原车实现维护，不猜事务偏移。 */
internal fun interface GalaxyDimMediaOutput {
    fun send(value: CarPlayNowPlaying, artwork: Uri?, track: String, source: Int)
}

internal class GalaxyDimMediaContract(loader: ClassLoader, binder: IBinder) : GalaxyDimMediaOutput {
    private val info = Class.forName("com.autolink.adapterbinder.PlayMediaInfo", true, loader)
    private val service = Class.forName(DESCRIPTOR, true, loader)
    private val proxy: Any
    private val send = service.getMethod("sendMessage", info, Int::class.javaPrimitiveType)
    private val constructor = info.getConstructor()
    private val fields = mapOf(
        "Source_type" to Int::class.javaPrimitiveType!!, "Uuid" to String::class.java,
        "Title" to String::class.java, "Album" to String::class.java, "Artist" to String::class.java,
        "ArtworkUrl" to Uri::class.java, "Duration" to Long::class.javaPrimitiveType!!,
        "Current_progress" to Long::class.javaPrimitiveType!!, "Playback_status" to Int::class.javaPrimitiveType!!,
    ).mapValues { (name, type) -> info.getMethod("set$name", type) }

    init {
        check(binder.interfaceDescriptor == DESCRIPTOR)
        check(Parcelable::class.java.isAssignableFrom(info))
        proxy = requireNotNull(Class.forName("$DESCRIPTOR\$Stub", true, loader)
            .getMethod("asInterface", IBinder::class.java).invoke(null, binder))
        check(service.isInstance(proxy))
    }

    override fun send(value: CarPlayNowPlaying, artwork: Uri?, track: String, source: Int) {
        require(value.playbackKnown && !value.title.isNullOrBlank())
        val payload = constructor.newInstance()
        fun set(name: String, value: Any?) { fields.getValue(name).invoke(payload, value) }
        set("Source_type", source)
        set("Uuid", track)
        set("Title", value.title)
        set("Album", value.album.orEmpty())
        set("Artist", value.artist.orEmpty())
        set("ArtworkUrl", artwork)
        set("Duration", (value.durationMillis ?: 0L).coerceAtLeast(0))
        set("Current_progress", (value.elapsedMillis ?: 0L).coerceAtLeast(0))
        set("Playback_status", if (value.playing) 1 else 0)
        send.invoke(proxy, payload, 0)
    }

    companion object {
        const val DESCRIPTOR = "com.autolink.adapterbinder.IDimMediaInteractioncService"
        fun load(context: Context, binder: IBinder): GalaxyDimMediaContract {
            for (name in listOf(GalaxyDimMediaProbe.PACKAGE, "com.autolink.audioextservice",
                    "com.autolink.bluetoothservice", "com.kotei.psdbluetooth")) {
                try {
                    val installed = context.createPackageContext(name, Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)
                    return GalaxyDimMediaContract(installed.classLoader, binder)
                } catch (_: Exception) { /* 只接受已核对的原厂接口包，缺少实现时尝试下一个。 */ }
                  catch (_: LinkageError) { }
            }
            throw IllegalStateException("DIM_CONTRACT_UNAVAILABLE")
        }
    }
}
