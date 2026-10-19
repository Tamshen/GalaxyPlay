package com.shilapi.xcertplay

import android.content.Context
import android.net.Uri
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.vendor.SdkSubclass
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

/** 使用已安装 SDK，所有服务调用保留本应用包名和 UID，不使用旧的空实现重载。 */
internal class L7ReflectiveMediaCenter(
    context: Context,
    private val localPlayback: Boolean = true,
    private val sdkLoader: (() -> L7VendorSdk.Loaded)? = null,
    private val serviceEvidence: () -> Int? = { L7VendorServiceProbe.inspect(context.applicationContext)["mediaProviderEasSupport"]?.toIntOrNull() },
) : L7MediaCenterPort {
    private val app = context.applicationContext
    private val vehicle = L7AudioTemplates.model(context).id
    private var api: Any? = null
    private var apiType: Class<*>? = null
    private var client: Any? = null
    private var token: Any? = null
    private var contract: GalaxyOemMediaContract? = null
    private var clientHandler: ((Long) -> InvocationHandler)? = null
    @Volatile private var registration = 0L
    private var tokenBinder: android.os.IBinder? = null
    private var tokenDeath: android.os.IBinder.DeathRecipient? = null
    private var apiReady: ((Boolean) -> Unit)? = null
    private var infoClass: Class<*>? = null
    @Volatile private var latest = CarPlayNowPlaying()
    @Volatile private var info: Any? = null
    @Volatile private var snapshotRevision = 0L
    private val trackSession = java.util.UUID.randomUUID().toString()
    private var track = 0L
    override var source = L7MediaCenterPort.CARPLAY_SOURCE
        private set
    @Volatile private var valid = true

    override fun initialize(ready: (Boolean) -> Unit, command: (Int) -> Boolean,
                            focus: (String?) -> Unit, selected: (Int) -> Boolean) {
        if (!valid) return
        L7DebugLog.record("MediaCenter: directRegistration vehicle=$vehicle")
        val sdk = sdkLoader?.invoke() ?: GalaxyMediaSdk.load(app)
        apiReady = ready
        val apiClass = Class.forName(API, true, sdk.loader)
        apiType = apiClass
        val clientClass = Class.forName(CLIENT, false, sdk.loader)
        infoClass = Class.forName(INFO, false, sdk.loader)
        val verified = GalaxyOemMediaContract(apiClass, clientClass, requireNotNull(infoClass))
        contract = verified
        source = L7MediaSourcePolicy.resolve(serviceEvidence(), requireNotNull(infoClass))
        val callbackClass = Class.forName("com.ecarx.eas.sdk.ECarXApiClient\$Callback", false, sdk.loader)
        api = apiClass.getMethod("get", Context::class.java).invoke(null, app)
            ?: throw IllegalStateException("SDK_API_EMPTY")
        L7SteeringDiagnostics.store.state("customActionObserver", "available=${verified.clientMethods.any { it.name == "onCustomAction" }}")
        clientHandler = { epoch -> GalaxyOemMediaCallbacks(source, { valid && registration == epoch },
            { latest }, { info }, command, focus, selected) }
        val callback = Proxy.newProxyInstance(sdk.loader, arrayOf(callbackClass)) { proxy, method, args ->
            when (method.name) {
                "onAPIReady" -> { if (valid) ready(args?.firstOrNull() == true); null }
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "L7MediaReadyCallback"
                else -> null
            }
        }
        L7DebugLog.record("MediaCenter: sdk source=${sdk.source} uid=${android.os.Process.myUid()} package=${app.packageName} " +
            "mediaSource=$source mediaSessionLinked=${verified.linkedSession}")
        if (valid) apiClass.getMethod("init", Context::class.java, callbackClass).invoke(api, app, callback)
    }

    override fun register(): Boolean {
        if (!valid) return false
        forgetToken()
        val verified = requireNotNull(contract)
        val epoch = ++registration
        val callback = SdkSubclass.create(verified.client, verified.clientMethods, requireNotNull(clientHandler)(epoch))
        client = callback
        val received = verified.register(requireNotNull(api), app.packageName, callback)
        val binder = (received as? android.os.IInterface)?.asBinder()
        if (binder == null || !binder.isBinderAlive) {
            registration++
            L7DebugLog.record("MediaCenter: registration tokenValid=false reason=INVALID_OR_DEAD_BINDER")
            return false
        }
        token = received
        val death = android.os.IBinder.DeathRecipient {
            if (valid && registration == epoch) apiReady?.invoke(false)
        }
        tokenBinder = binder
        tokenDeath = death
        binder.linkToDeath(death, 0)
        val catalog = GalaxyOemSourceRegistration.ensure(app, requireNotNull(api), requireNotNull(apiType), source) {
            valid && registration == epoch && binder.isBinderAlive
        }
        L7DebugLog.record("MediaCenter: sourceCatalog=$catalog source=$source")
        if (!valid || registration != epoch || !binder.isBinderAlive) return false
        L7DebugLog.record("MediaCenter: registration mediaSessionLinked=${verified.linkedSession} source=$source tokenValid=true")
        return true
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
        val revision = ++snapshotRevision
        // 初始化时手机播放状态可能尚未回传，不能让 SDK getter 把它解释成暂停。
        if (!value.playbackKnown) { info = null; return }
        artwork?.let { GalaxyMediaArtworkAccess.grant(app, it, value.artworkTransferId) }
        val cls = requireNotNull(infoClass)
        val snapshotTrack = "$trackSession:$track"
        val artworkGetterReported = java.util.concurrent.atomic.AtomicBoolean()
        info = SdkSubclass.create(cls, requireNotNull(contract).infoMethods,
            GalaxyOemPlaybackSnapshot(app, value, source, snapshotTrack, { valid && snapshotRevision == revision }, {
                synchronized(this) {
                    if (!valid || snapshotRevision != revision) null else {
                        val caller = android.os.Binder.getCallingUid()
                        var failures = 0
                        try {
                            if (artwork != null) app.packageManager.getPackagesForUid(caller)?.forEach { name ->
                                if (name != app.packageName) try {
                                    app.grantUriPermission(name, artwork, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                } catch (_: Exception) { failures++ }
                            }
                        } catch (_: Exception) { failures++ }
                        if (artworkGetterReported.compareAndSet(false, true)) {
                            L7DebugLog.record("MediaCenter: artworkGetter transferId=${value.artworkTransferId ?: "none"} " +
                                "coverKey=${artwork?.let(L7MediaArtworkProvider::diagnosticKey) ?: "none"} " +
                                "available=${artwork != null} callerOwn=${caller == android.os.Process.myUid()} grantFailures=$failures")
                        }
                        artwork
                    }
                }
            }, localPlayback && GalaxyVehiclePreferences.steering(app)))
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
        registration++
        snapshotRevision++
        info = null
        forgetToken()
        return call("unregister", arrayOf(Any::class.java), old) == true
    }
    private fun forgetToken() {
        tokenDeath?.let { death -> runCatching { tokenBinder?.unlinkToDeath(death, 0) } }
        tokenDeath = null
        tokenBinder = null
        token = null
    }
    @Synchronized override fun invalidate() {
        valid = false
        snapshotRevision++
        info = null
        latest = CarPlayNowPlaying()
    }
    override fun close() {
        invalidate()
        forgetToken()
        client = null
        clientHandler = null
        apiReady = null
        info = null
        latest = CarPlayNowPlaying()
        api?.let { target -> requireNotNull(apiType).getMethod("release").invoke(target) }
        api = null
        contract = null
    }
    companion object {
        const val API = "com.ecarx.eas.sdk.mediacenter.MediaCenterAPI"
        const val CLIENT = "com.ecarx.eas.sdk.mediacenter.MusicClient"
        const val INFO = "com.ecarx.eas.sdk.mediacenter.MusicPlaybackInfo"
    }
}
