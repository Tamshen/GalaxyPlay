package com.shilapi.xcertplay

import android.content.Context

/** 仅读取类和方法签名；禁止 get/init/create/register，以及类静态初始化。 */
internal object L7SdkContractProbe {
    fun inspect(context: Context): Map<String, String?> {
        val result = linkedMapOf<String, String?>("effectiveCall" to "CLASS_SIGNATURE_AND_PACKAGE_QUERY_ONLY")
        result.putAll(L7VendorServiceProbe.inspect(context))
        check(context, L7ReflectiveMediaCenter.API, "media", result) { type, loader ->
            val client = Class.forName(L7ReflectiveMediaCenter.CLIENT, false, loader)
            val info = Class.forName(L7ReflectiveMediaCenter.INFO, false, loader)
            val callback = Class.forName("com.ecarx.eas.sdk.ECarXApiClient\$Callback", false, loader)
            type.getMethod("get", Context::class.java)
            type.getMethod("init", Context::class.java, callback)
            type.getMethod("registerMusic", String::class.java, client)
            type.getMethod("requestPlay", Any::class.java)
            type.getMethod("updateCurrentSourceType", Any::class.java, Int::class.javaPrimitiveType)
            client.getConstructor()
            info.getConstructor()
            for (name in listOf("onPlay", "onPause", "onNext", "onPrevious"))
                require(client.getMethod(name).returnType == Boolean::class.javaPrimitiveType)
            for (name in listOf("getTitle", "getArtist", "getAlbum", "getUuid"))
                require(info.getMethod(name).returnType == String::class.java)
            require(info.getMethod("getDuration").returnType == Long::class.javaPrimitiveType)
            require(info.getMethod("getPlaybackStatus").returnType == Int::class.javaPrimitiveType)
            require(info.getMethod("getArtwork").returnType == android.net.Uri::class.java)
            type.getMethod("updateMusicPlaybackState", Any::class.java, info)
            type.getMethod("updateMediaSourceTypeList", Any::class.java, IntArray::class.java)
            type.getMethod("updateCurrentProgress", Any::class.java, Long::class.javaPrimitiveType)
            type.getMethod("queryCurrentFocusClient", Any::class.java)
            type.getMethod("unregister", Any::class.java)
            result["mediaCallbackKind"] = if (client.isInterface) "INTERFACE" else "CLASS"
            result["mediaSourceEvidence"] = "CARPLAY_REFERENCE_13_L7_ACCEPTANCE_UNTESTED"
        }
        check(context, L7ReflectiveNavigation.API, "navigation", result) { type, loader ->
            Class.forName("ecarx.fw.api.ECarXAPI", false, loader).getMethod("creator", Class::class.java)
            Class.forName("ecarx.fw.api.ICreator", false, loader).getMethod("create", Context::class.java)
            val callback = type.methods.single { it.name == "registerNavigationInteractionCallback" && it.parameterCount == 1 }.parameterTypes.single()
            type.getMethod("unregisterNavigationInteractionCallback", callback)
            type.getMethod("notifyTurnByTurnStarted")
            type.getMethod("notifyTurnByTurnStopped")
            type.getMethod("updateNextGuidancePointName", String::class.java)
            result["navigationMapping"] = "START_STOP_ROAD_ONLY_OTHER_UNITS_UNCONFIRMED"
        }
        return result
    }
    private fun check(context: Context, name: String, key: String, result: MutableMap<String, String?>,
                      verify: (Class<*>, ClassLoader) -> Unit) {
        try {
            val loaded = L7VendorSdk(context).load(name)
            result["${key}SdkSource"] = loaded.source
            verify(Class.forName(name, false, loaded.loader), loaded.loader)
            result["${key}Contract"] = "VISIBLE_MATCH"
        } catch (error: Throwable) {
            if (error !is Exception && error !is LinkageError) throw error
            result["${key}Contract"] = when (error) {
                is ClassNotFoundException -> "NOT_VISIBLE_OR_UNINSTALLED"
                is NoSuchMethodException, is IllegalArgumentException -> "SIGNATURE_MISMATCH"
                is SecurityException -> "QUERY_DENIED"
                else -> "QUERY_FAILED"
            }
            result["${key}ExceptionType"] = error.javaClass.simpleName
        }
    }
}
