package com.shilapi.xcertplay

import android.content.Context
import android.net.Uri
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.vendor.SdkSubclass
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/** 使用已安装 SDK，所有服务调用保留本应用包名和 UID，不使用旧的空实现重载。 */
internal class L7ReflectiveMediaCenter(context: Context) : L7MediaCenterPort {
    private val app = context.applicationContext
    private var api: Any? = null
    private var apiType: Class<*>? = null
    private var client: Any? = null
    private var token: Any? = null
    private var loader: ClassLoader? = null
    private var infoClass: Class<*>? = null
    @Volatile private var latest = CarPlayNowPlaying()
    @Volatile private var info: Any? = null
    private var track = 0L
    @Volatile private var valid = true

    override fun initialize(ready: (Boolean) -> Unit, command: (Int) -> Boolean,
                            focus: (String?) -> Unit, selected: (Int) -> Boolean) {
        if (!valid) return
        val sdk = L7VendorSdk(app).load(API)
        loader = sdk.loader
        val apiClass = Class.forName(API, true, sdk.loader)
        apiType = apiClass
        val clientClass = Class.forName(CLIENT, false, sdk.loader)
        infoClass = Class.forName(INFO, false, sdk.loader)
        val callbackClass = Class.forName("com.ecarx.eas.sdk.ECarXApiClient\$Callback", false, sdk.loader)
        api = apiClass.getMethod("get", Context::class.java).invoke(null, app)
            ?: throw IllegalStateException("SDK_API_EMPTY")
        val names = setOf("onPlay", "onPause", "onNext", "onPrevious", "onMediaCenterFocusChanged",
            "onSourceSelected", "getCurrentSourceType", "getMediaSourceTypeList", "getCurrentProgress", "getMusicPlaybackInfo")
        client = SdkSubclass.create(clientClass, clientClass.methods.filter { it.name in names }.toTypedArray(), InvocationHandler { _, method, args ->
            when (method.name) {
                "onPlay" -> valid && command(CarPlayMediaButton.PLAY)
                "onPause" -> valid && command(CarPlayMediaButton.PAUSE)
                "onNext" -> valid && command(CarPlayMediaButton.NEXT)
                "onPrevious" -> valid && command(CarPlayMediaButton.PREVIOUS)
                "onMediaCenterFocusChanged" -> { if (valid) focus(args?.firstOrNull() as? String); null }
                "onSourceSelected" -> valid && selected(args?.firstOrNull() as? Int ?: -1)
                "getCurrentSourceType" -> if (valid) L7MediaCenterPort.CARPLAY_SOURCE else -1
                "getMediaSourceTypeList" -> if (valid) intArrayOf(L7MediaCenterPort.CARPLAY_SOURCE) else intArrayOf()
                "getCurrentProgress" -> if (valid) latest.elapsedMillis ?: 0L else 0L
                "getMusicPlaybackInfo" -> if (valid) info else null
                else -> null
            }
        })
        val callback = Proxy.newProxyInstance(sdk.loader, arrayOf(callbackClass)) { proxy, method, args ->
            when (method.name) {
                "onAPIReady" -> { if (valid) ready(args?.firstOrNull() == true); null }
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "L7MediaReadyCallback"
                else -> null
            }
        }
        L7DebugLog.record("MediaCenter: sdk source=${sdk.source} uid=${android.os.Process.myUid()} package=${app.packageName}")
        if (valid) apiClass.getMethod("init", Context::class.java, callbackClass).invoke(api, app, callback)
    }

    override fun register(): Boolean {
        if (!valid) return false
        val client = client ?: return false
        token = requireNotNull(apiType).getMethod("registerMusic", String::class.java, client.javaClass.superclass)
            .invoke(api, app.packageName, client)
        return token != null
    }
    override fun sources(values: IntArray) = call("updateMediaSourceTypeList", arrayOf(Any::class.java, IntArray::class.java), token, values) == true
    override fun currentSource() { call("updateCurrentSourceType", arrayOf(Any::class.java, Int::class.javaPrimitiveType!!), token, L7MediaCenterPort.CARPLAY_SOURCE) }
    override fun focusClient() = call("queryCurrentFocusClient", arrayOf(Any::class.java), token) as? String
    override fun requestPlay() = call("requestPlay", arrayOf(Any::class.java), token) == true
    override fun progress(milliseconds: Long) { latest = latest.copy(elapsedMillis = milliseconds); call("updateCurrentProgress", arrayOf(Any::class.java, Long::class.javaPrimitiveType!!), token, milliseconds) }

    override fun update(value: CarPlayNowPlaying, artwork: Uri?): Boolean {
        if (!valid) return false
        if (latest.title != value.title || latest.artist != value.artist || latest.album != value.album ||
            latest.artworkTransferId != value.artworkTransferId || latest.durationMillis != value.durationMillis) track++
        latest = value
        val cls = requireNotNull(infoClass)
        val snapshotTrack = track.toString()
        val getters = setOf("getTitle", "getArtist", "getAlbum", "getDuration", "getArtwork", "getSourceType",
            "getPlaybackStatus", "getPackageName", "getAppName", "getUuid", "isSupportCollect", "isSupportDownload", "isSupportLoopModeSwitch")
        info = SdkSubclass.create(cls, cls.methods.filter { it.name in getters }.toTypedArray(), InvocationHandler { _, method, _ ->
            if (!valid) return@InvocationHandler when (method.returnType) {
                java.lang.Long.TYPE -> 0L
                java.lang.Integer.TYPE -> 0
                java.lang.Boolean.TYPE -> false
                else -> null
            }
            when (method.name) {
                "getTitle" -> value.title
                "getArtist" -> value.artist
                "getAlbum" -> value.album
                "getDuration" -> value.durationMillis ?: 0L
                "getArtwork" -> synchronized(this) {
                    if (!valid || artwork == null) null else {
                        val caller = android.os.Binder.getCallingUid()
                        app.packageManager.getPackagesForUid(caller)?.forEach { name ->
                            if (name != app.packageName) app.grantUriPermission(name, artwork, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        artwork
                    }
                }
                "getSourceType" -> L7MediaCenterPort.CARPLAY_SOURCE
                "getPlaybackStatus" -> if (value.playing) 1 else 0
                "getPackageName" -> app.packageName
                "getAppName" -> "L7 CarPlay"
                "getUuid" -> snapshotTrack
                else -> false
            }
        })
        return call("updateMusicPlaybackState", arrayOf(Any::class.java, cls), token, info) == true
    }

    private fun call(name: String, parameters: Array<Class<*>>, vararg args: Any?): Any? =
        if (!valid && name != "unregister" && name != "updateMediaSourceTypeList") null
        else requireNotNull(apiType).getMethod(name, *parameters).invoke(api, *args)

    override fun unregister(): Boolean {
        val old = token ?: return false
        token = null
        return call("unregister", arrayOf(Any::class.java), old) == true
    }
    @Synchronized override fun invalidate() {
        valid = false
        info = null
        latest = CarPlayNowPlaying()
    }
    override fun close() {
        invalidate()
        token = null
        client = null
        info = null
        latest = CarPlayNowPlaying()
        api?.let { target -> apiType?.methods?.find { it.name == "release" && it.parameterCount == 0 }?.invoke(target) }
        api = null
    }
    companion object {
        const val API = "com.ecarx.eas.sdk.mediacenter.MediaCenterAPI"
        const val CLIENT = "com.ecarx.eas.sdk.mediacenter.MusicClient"
        const val INFO = "com.ecarx.eas.sdk.mediacenter.MusicPlaybackInfo"
    }
}
