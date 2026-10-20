package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.hud.CarPlayNavigationSnapshot
import java.io.Closeable
import java.lang.reflect.Proxy

internal interface GalaxyNavigationListener {
    fun status(value: Int)
    fun guidance(value: CarPlayNavigationSnapshot, vendor: Int)
}

internal interface GalaxyNavigationBackend : Closeable {
    fun initialize(ready: (Boolean) -> Unit)
    fun register(listener: (GalaxyNavigationListener) -> Unit): Int
}

internal class GalaxyNavigationRegistrationException(val code: Int) : IllegalStateException("MAP_REGISTRATION_REJECTED")

/** 仅加载原车公开 SDK；初始化、包名和 UID 始终使用 Galaxy 自己的身份。 */
internal class GalaxyNavigationSdk(
    context: Context,
    private val snapshot: () -> CarPlayNavigationSnapshot?,
    private val contract: GalaxyNavigationContract = load(context),
) : GalaxyNavigationBackend {
    private val app = context.applicationContext
    private var api: Any? = null
    private var client: Any? = null
    private var readyBinding: GalaxyNavigationReadyCallback.Binding? = null
    @Volatile private var valid = true
    @Volatile private var revision = 0L
    @Volatile private var mapVendor: Int? = null

    override fun initialize(ready: (Boolean) -> Unit) {
        val target = requireNotNull(contract.get.invoke(null, app)) { "MAP_API_NULL" }
        // 基类保留了返回 null 的占位实现，不能把它当作可工作的导航服务。
        check(target.javaClass.getMethod(contract.register.name, *contract.register.parameterTypes).declaringClass != contract.api) {
            "MAP_API_PLACEHOLDER"
        }
        api = target
        val binding = GalaxyNavigationReadyCallback.bind(contract.callback) { if (valid) ready(it) }
        readyBinding = binding
        contract.initialize.invoke(target, app, binding.callback)
    }

    override fun register(listener: (GalaxyNavigationListener) -> Unit): Int {
        val epoch = ++revision
        val callback = proxy(contract.client) { name, args ->
            if (valid && epoch == revision) when (name) {
                "setMapEventListener" -> args.firstOrNull()?.let { remote ->
                    listener(object : GalaxyNavigationListener {
                        override fun status(value: Int) {
                            check(valid && epoch == revision)
                            contract.status.invoke(remote, value)
                        }
                        override fun guidance(value: CarPlayNavigationSnapshot, vendor: Int) {
                            check(valid && epoch == revision)
                            contract.guidance.invoke(remote, contract.model(value, app.packageName, vendor))
                        }
                    })
                }
                "invokeMapAPI" -> contract.failure(args.firstOrNull(), 10028)
                else -> respond(name, args)
            } else null
        }
        client = callback
        val result = contract.register.invoke(requireNotNull(api), contract.registrationInfo(app.packageName),
            contract.clientArray(callback)) ?: error("MAP_REGISTRATION_NULL")
        if (contract.error.isInstance(result)) {
            throw GalaxyNavigationRegistrationException(contract.errorCode.invoke(result) as Int)
        }
        check(contract.base.isInstance(result)) { "MAP_REGISTRATION_TYPE" }
        return (contract.vendor.invoke(result) as Int).also { mapVendor = it }
    }

    private fun respond(name: String, args: Array<out Any?>) {
        val response = args.lastOrNull()?.takeIf(contract.reply::isInstance) ?: return
        val value = snapshot()
        val vendor = mapVendor
        if (name == "getGuideInfo" && value?.active == true && vendor != null) {
            contract.success.invoke(response, contract.model(value, app.packageName, vendor))
        } else {
            // 手机路线控制和地图画面投屏未实现，明确回失败，不能假装已经取消手机导航。
            contract.fail.invoke(response, contract.failure(args.firstOrNull(), if (name == "getGuideInfo") 10004 else 10028))
        }
    }

    override fun close() {
        if (!valid) return
        valid = false
        readyBinding?.close()
        readyBinding = null
        revision++
        mapVendor = null
        val target = api
        val callback = client
        api = null
        client = null
        if (target != null) try {
            if (callback != null) contract.unregister.invoke(target, contract.clientArray(callback))
        } finally {
            contract.release.invoke(target)
        }
    }

    private fun proxy(type: Class<*>, action: (String, Array<out Any?>) -> Any?): Any =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { self, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(self)
                "equals" -> self === args?.firstOrNull()
                "toString" -> "GalaxyNavigationCallback"
                else -> action(method.name, args ?: emptyArray())
            }
        }

    companion object {
        fun load(context: Context): GalaxyNavigationContract {
            var contract: GalaxyNavigationContract? = null
            L7VendorSdk(context).load("com.ecarx.eas.framework.sdk.navi.MapAPI") {
                contract = GalaxyNavigationContract(it)
            }
            return requireNotNull(contract)
        }
    }
}
