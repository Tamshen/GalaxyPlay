package com.shilapi.xcertplay

import java.io.Closeable
import java.lang.reflect.Proxy

/** 部分原厂 SDK 的 release 为空且静态保存 Callback；同一 SDK 只交付一个可解绑的回调。 */
internal object GalaxyNavigationReadyCallback {
    class Binding(val callback: Any, private val detach: () -> Unit) : Closeable {
        override fun close() = detach()
    }
    private class Slot(type: Class<*>) {
        @Volatile var sink: ((Boolean) -> Unit)? = null
        val callback: Any = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { self, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(self)
                "equals" -> self === args?.firstOrNull()
                "toString" -> "GalaxyNavigationReadyCallback"
                "onAPIReady" -> { sink?.invoke(args?.firstOrNull() == true); null }
                else -> null
            }
        }
    }
    private val slots = java.util.IdentityHashMap<Class<*>, Slot>()
    @Synchronized fun bind(type: Class<*>, ready: (Boolean) -> Unit): Binding {
        val slot = slots.getOrPut(type) { Slot(type) }
        slot.sink = ready
        return Binding(slot.callback) { synchronized(this) {
            if (slot.sink === ready) slot.sink = null
        } }
    }
}
