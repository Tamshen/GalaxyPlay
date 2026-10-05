package com.shilapi.xcertplay

import android.content.Context
import android.net.Uri
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.vendor.SdkSubclass
import java.lang.reflect.InvocationHandler
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
    private val trackSession = java.util.UUID.randomUUID().toString()
    private var track = 0L
    override var source = L7MediaCenterPort.CARPLAY_SOURCE
        private set
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
        source = L7MediaSourcePolicy.resolve(
            L7VendorServiceProbe.inspect(app)["mediaProviderEasSupport"]?.toIntOrNull(), requireNotNull(infoClass))
        val callbackClass = Class.forName("com.ecarx.eas.sdk.ECarXApiClient\$Callback", false, sdk.loader)
        api = apiClass.getMethod("get", Context::class.java).invoke(null, app)
            ?: throw IllegalStateException("SDK_API_EMPTY")
        val names = setOf("onPlay", "onPause", "onNext", "onPrevious", "onMediaCenterFocusChanged",
            "onSourceSelected", "getCurrentSourceType", "getMediaSourceTypeList", "getCurrentProgress", "getMusicPlaybackInfo") +
            if (L7AudioTemplates.model(app) != L7AudioTemplates.Model.L7) setOf("onCustomAction") else emptySet()
        val methods = clientClass.methods.filter { it.name in names && (it.name != "onCustomAction" ||
            (it.returnType == Void.TYPE && it.parameterTypes.contentEquals(arrayOf(android.os.Bundle::class.java)) &&
                !java.lang.reflect.Modifier.isFinal(it.modifiers) && !java.lang.reflect.Modifier.isStatic(it.modifiers))) }
        if (L7AudioTemplates.model(app) != L7AudioTemplates.Model.L7)
            L7SteeringDiagnostics.store.state("customActionObserver", "available=${methods.any { it.name == "onCustomAction" }}")
        client = SdkSubclass.create(clientClass, methods.toTypedArray(), InvocationHandler { _, method, args ->
            when (method.name) {
                "onCustomAction" -> { if (valid) VehicleSteeringInputLog.customAction(args?.firstOrNull() as? android.os.Bundle); null }
                "onPlay" -> valid && command(CarPlayMediaButton.PLAY)
                "onPause" -> valid && command(CarPlayMediaButton.PAUSE)
                "onNext" -> valid && command(CarPlayMediaButton.NEXT)
                "onPrevious" -> valid && command(CarPlayMediaButton.PREVIOUS)
                "onMediaCenterFocusChanged" -> { if (valid) focus(args?.firstOrNull() as? String); null }
                "onSourceSelected" -> valid && selected(args?.firstOrNull() as? Int ?: -1)
                "getCurrentSourceType" -> if (valid) source else -1
                "getMediaSourceTypeList" -> if (valid) intArrayOf(source) else intArrayOf()
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
        val type = requireNotNull(apiType)
        val linked = type.methods.singleOrNull { it.name == "registerMusic" &&
            it.parameterTypes.contentEquals(arrayOf(String::class.java, client.javaClass.superclass, String::class.java)) }
        token = if (linked != null) linked.invoke(api, app.packageName, client, app.packageName)
            else type.getMethod("registerMusic", String::class.java, client.javaClass.superclass)
                .invoke(api, app.packageName, client)
        L7DebugLog.record("MediaCenter: registration mediaSessionLinked=${linked != null} source=$source tokenValid=${token != null}")
        return token != null
    }
    override fun sources(values: IntArray) = call("updateMediaSourceTypeList", arrayOf(Any::class.java, IntArray::class.java), token, values) == true
    override fun currentSource() { call("updateCurrentSourceType", arrayOf(Any::class.java, Int::class.javaPrimitiveType!!), token, source) }
    override fun focusClient() = call("queryCurrentFocusClient", arrayOf(Any::class.java), token) as? String
    override fun requestPlay() = call("requestPlay", arrayOf(Any::class.java), token) == true
    override fun progress(milliseconds: Long) { latest = latest.copy(elapsedMillis = milliseconds); call("updateCurrentProgress", arrayOf(Any::class.java, Long::class.javaPrimitiveType!!), token, milliseconds) }

    override fun prepare(value: CarPlayNowPlaying, artwork: Uri?) {
        if (!valid) return
        if (latest.title != value.title || latest.artist != value.artist || latest.album != value.album ||
            latest.artworkTransferId != value.artworkTransferId || latest.durationMillis != value.durationMillis) track++
        latest = value
        // 初始化时手机播放状态可能尚未回传，不能让 SDK getter 把它解释成暂停。
        if (!value.playbackKnown) { info = null; return }
        // 封面会由 EAS 转交媒体中心读取；只给已确认的链路包读取权，不开放 provider。
        if (artwork != null) for (name in listOf(L7VendorServiceProbe.PACKAGE, "com.ecarx.sdk.openapi")) {
            try {
                app.packageManager.getApplicationInfo(name, 0)
                app.grantUriPermission(name, artwork, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                L7DebugLog.record("MediaCenter: artworkGrant transferId=${value.artworkTransferId ?: "none"} coverKey=${L7MediaArtworkProvider.diagnosticKey(artwork)} result=GRANTED target=$name")
            } catch (error: Exception) {
                L7DebugLog.record("MediaCenter: artworkGrant transferId=${value.artworkTransferId ?: "none"} result=FAILED target=$name exceptionType=${error.javaClass.simpleName}")
            }
        }
        val cls = requireNotNull(infoClass)
        val snapshotTrack = "$trackSession:$track"
        val artworkGetterReported = java.util.concurrent.atomic.AtomicBoolean()
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
                    if (!valid) null else {
                        val caller = android.os.Binder.getCallingUid()
                        var failures = 0
                        try {
                            if (artwork != null) app.packageManager.getPackagesForUid(caller)?.forEach { name ->
                                if (name != app.packageName) try {
                                    app.grantUriPermission(name, artwork, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                } catch (_: Exception) { failures++ }
                            }
                        } catch (_: Exception) { failures++ }
                        // 每个快照只记一次 getter；授权失败不抛出 Binder 回调，读取结果由 provider 记录。
                        if (artworkGetterReported.compareAndSet(false, true)) {
                            L7DebugLog.record("MediaCenter: artworkGetter transferId=${value.artworkTransferId ?: "none"} " +
                                "coverKey=${artwork?.let(L7MediaArtworkProvider::diagnosticKey) ?: "none"} " +
                                "available=${artwork != null} callerOwn=${caller == android.os.Process.myUid()} grantFailures=$failures")
                        }
                        artwork
                    }
                }
                "getSourceType" -> source
                "getPlaybackStatus" -> if (value.playing) 1 else 0
                "getPackageName" -> app.packageName
                "getAppName" -> app.getString(R.string.app_name)
                "getUuid" -> snapshotTrack
                else -> false
            }
        })
    }

    override fun update(value: CarPlayNowPlaying, artwork: Uri?): Boolean {
        if (!valid) return false
        return call("updateMusicPlaybackState", arrayOf(Any::class.java, requireNotNull(infoClass)), token, info) == true
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
