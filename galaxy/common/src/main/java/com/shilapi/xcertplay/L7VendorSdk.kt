package com.shilapi.xcertplay

import android.content.Context

/** SDK 实现在已安装车机组件中；所有初始化仍传本应用 Context，UID 不发生变化。 */
internal class L7VendorSdk(private val context: Context) {
    data class Loaded(val loader: ClassLoader, val source: String)

    fun load(name: String, preferredPackages: List<String> = if (name.startsWith("com.ecarx.eas.sdk.mediacenter."))
        GalaxyMediaReportingChannel.load(context).sdkPackages else emptyList(),
        verify: (ClassLoader) -> Unit = {}): Loaded {
        val packages = if (name.startsWith("ecarx.fw.api.")) listOf("com.flyme.auto.map", "com.geely.linkhmi")
            else listOf("com.geely.linkhmi")
        val candidates = (preferredPackages + "APP_OR_FRAMEWORK" + packages).distinct()
        var mismatch: Exception? = null
        var unavailable: Throwable? = null
        for (packageName in candidates) try {
            // 只加载 SDK 类型，不运行原厂 Application，也不拿其 Context 调用系统或媒体服务。
            val loader = if (packageName == "APP_OR_FRAMEWORK") context.classLoader else
                context.createPackageContext(packageName, Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY).classLoader
            Class.forName(name, false, loader)
            verify(loader)
            return Loaded(loader, packageName)
        } catch (error: Exception) {
            unavailable = error
            if (error is NoSuchMethodException || error is NoSuchFieldException ||
                error is IllegalArgumentException || error is IllegalStateException)
                mismatch = error
        } catch (error: LinkageError) { unavailable = error }
        mismatch?.let { throw it }
        throw ClassNotFoundException(name, unavailable)
    }
}
