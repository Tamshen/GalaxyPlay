package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.vendor.SdkSubclass
import java.io.Closeable
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

internal interface L7NavigationPort : Closeable {
    fun initialize()
    fun ready(): Boolean = true
    fun start()
    fun road(value: String)
    fun stop()
    fun invalidate() {}
}

/** 直接使用地图样本引用的框架 API，不初始化地图 Application 或操作车辆信号。 */
internal class L7ReflectiveNavigation(context: Context) : L7NavigationPort {
    private val app = context.applicationContext
    private var api: Any? = null
    private var apiType: Class<*>? = null
    @Volatile private var valid = true
    private var callback: Any? = null
    private var callbackType: Class<*>? = null
    private var serviceInstance: Any? = null
    private var serviceGetter: java.lang.reflect.Method? = null

    override fun initialize() {
        if (!valid) return
        val sdk = L7VendorSdk(app).load(API)
        val type = Class.forName(API, false, sdk.loader)
        apiType = type
        val factory = Class.forName("ecarx.fw.api.ECarXAPI", true, sdk.loader)
            .getMethod("creator", Class::class.java).invoke(null, type)
        if (!valid) return
        api = Class.forName("ecarx.fw.api.ICreator", false, sdk.loader)
            .getMethod("create", Context::class.java).invoke(factory, app)
            ?: throw IllegalStateException("SDK_API_EMPTY")
        val instance = Class.forName("com.autolink.adaptersrv.diminteraction.EcarxNaviInstance", false, sdk.loader)
        serviceInstance = instance.getMethod("getInstance", Context::class.java).invoke(null, app)
        serviceGetter = instance.getMethod("getService")
        val register = type.methods.single { it.name == "registerNavigationInteractionCallback" && it.parameterCount == 1 }
        val callbackClass = register.parameterTypes.single()
        val handler = InvocationHandler { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "L7NavigationCallback"
                else -> { if (valid) L7DebugLog.record("Navigation: callback method=${method.name}"); null }
            }
        }
        callback = if (callbackClass.isInterface) Proxy.newProxyInstance(sdk.loader, arrayOf(callbackClass), handler)
            else SdkSubclass.create(callbackClass, callbackClass.methods.filter {
                it.name in setOf("onDoInteractionAction", "onSearchAddress")
            }.toTypedArray(), handler)
        callbackType = callbackClass
        if (valid) register.invoke(api, callback)
        L7DebugLog.record("Navigation: sdk source=${sdk.source} callbackRegistration=RETURNED_NO_ACK serviceReady=${ready()} authorization=UNCONFIRMED target=UNKNOWN")
    }
    override fun ready(): Boolean {
        val service = serviceGetter?.invoke(serviceInstance) as? android.os.IInterface ?: return false
        return service.asBinder().isBinderAlive
    }
    private fun requireReady() { check(ready()) { "SDK_SERVICE_NOT_READY" } }
    override fun start() { if (!valid) return; requireReady(); requireNotNull(apiType).getMethod("notifyTurnByTurnStarted").invoke(api) }
    override fun road(value: String) { if (!valid) return; requireReady(); requireNotNull(apiType).getMethod("updateNextGuidancePointName", String::class.java).invoke(api, value) }
    override fun stop() { requireReady(); requireNotNull(apiType).getMethod("notifyTurnByTurnStopped").invoke(api) }
    override fun invalidate() { valid = false }
    override fun close() {
        invalidate()
        val target = api
        try {
            callbackType?.let { if (target != null) apiType?.getMethod("unregisterNavigationInteractionCallback", it)?.invoke(target, callback) }
        } finally { callback = null; callbackType = null; api = null; serviceInstance = null; serviceGetter = null }
    }
    companion object { const val API = "ecarx.fw.api.diminteraction.EcarxNaviInteraction" }
}
