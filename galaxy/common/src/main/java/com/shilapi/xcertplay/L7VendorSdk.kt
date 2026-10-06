package com.shilapi.xcertplay

import android.content.Context

/** SDK 实现在已安装车机组件中；所有初始化仍传本应用 Context，UID 不发生变化。 */
internal class L7VendorSdk(private val context: Context) {
    data class Loaded(val loader: ClassLoader, val source: String)

    fun load(name: String): Loaded {
        if (visible(name, context.classLoader)) return Loaded(context.classLoader, "APP_OR_FRAMEWORK")
        val packages = if (name.startsWith("ecarx.fw.api.")) listOf("com.flyme.auto.map", "com.geely.linkhmi")
            else listOf("com.geely.linkhmi")
        var failure: Exception? = null
        for (packageName in packages) try {
            // 只加载 SDK 类型，不运行原厂 Application，也不拿其 Context 调用系统或媒体服务。
            val loader = context.createPackageContext(packageName, Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY).classLoader
            if (visible(name, loader)) return Loaded(loader, packageName)
        } catch (error: Exception) { failure = error }
        throw ClassNotFoundException(name, failure)
    }

    private fun visible(name: String, loader: ClassLoader): Boolean = try {
        Class.forName(name, false, loader)
        true
    } catch (_: ClassNotFoundException) { false }
}
