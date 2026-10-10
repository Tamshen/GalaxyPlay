package com.shilapi.xcertplay

import android.app.PendingIntent
import android.net.Uri
import android.os.Bundle
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** L6／L7 原厂样本共用的公开 SDK 契约；旧接口占位重载不能用于注册或上报。 */
internal class GalaxyOemMediaContract(val api: Class<*>, val client: Class<*>, val info: Class<*>) {
    val registration: Method = api.methods.firstOrNull {
        it.name == "registerMusic" && it.parameterTypes.contentEquals(arrayOf(String::class.java, client, String::class.java))
    } ?: api.getMethod("registerMusic", String::class.java, client)
    val linkedSession = registration.parameterCount == 3
    val clientMethods: Array<Method>
    val infoMethods: Array<Method>

    init {
        require(registration.returnType == Any::class.java) { "SDK_REGISTRATION_SIGNATURE_MISMATCH" }
        client.getConstructor()
        info.getConstructor()
        val controls = listOf("onPlay", "onPause", "onNext", "onPrevious", "onSourceSelected",
            "onMediaCenterFocusChanged", "getCurrentSourceType", "getMediaSourceTypeList",
            "getCurrentProgress", "getMusicPlaybackInfo")
        clientMethods = (controls.map { name -> when (name) {
            "onSourceSelected" -> method(client, name, Boolean::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
            "onMediaCenterFocusChanged" -> method(client, name, Void.TYPE, String::class.java)
            "getCurrentSourceType" -> method(client, name, Int::class.javaPrimitiveType!!)
            "getMediaSourceTypeList" -> method(client, name, IntArray::class.java)
            "getCurrentProgress" -> method(client, name, Long::class.javaPrimitiveType!!)
            "getMusicPlaybackInfo" -> method(client, name, info)
            else -> method(client, name, Boolean::class.javaPrimitiveType!!)
        } } + listOfNotNull(optional(client, "onCustomAction", Void.TYPE, Bundle::class.java),
            optional(client, "onSourceChanged", Boolean::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, String::class.java)))
            .toTypedArray()
        val strings = listOf("getTitle", "getArtist", "getAlbum", "getAppIcon", "getAppName", "getPackageName", "getUuid")
        val required = strings.map { method(info, it, String::class.java) } + listOf(
            method(info, "getDuration", Long::class.javaPrimitiveType!!), method(info, "getArtwork", Uri::class.java),
            method(info, "getSourceType", Int::class.javaPrimitiveType!!), method(info, "getPlaybackStatus", Int::class.javaPrimitiveType!!),
            method(info, "getLaunchIntent", PendingIntent::class.java), method(info, "getPlayerIntent", PendingIntent::class.java))
        val capabilities = listOf("isCollected", "isDownloaded", "isSupportCollect", "isSupportDownload",
            "isSupportLoopModeSwitch", "isSupportVrCtrlPlayStatus")
        val integers = listOf("getVip", "getPlayingMediaListType", "getPlayingItemPositionInQueue", "getRadioMode", "getDisplayId")
        infoMethods = (required + capabilities.mapNotNull { optional(info, it, Boolean::class.javaPrimitiveType!!) } +
            integers.mapNotNull { optional(info, it, Int::class.javaPrimitiveType!!) }).toTypedArray()
        for (name in listOf("requestPlay", "unregister")) method(api, name, Boolean::class.javaPrimitiveType!!, Any::class.java, overridable = false)
        method(api, "updateMusicPlaybackState", Boolean::class.javaPrimitiveType!!, Any::class.java, info, overridable = false)
        method(api, "updateMediaSourceTypeList", Boolean::class.javaPrimitiveType!!, Any::class.java, IntArray::class.java, overridable = false)
        method(api, "updateCurrentProgress", Void.TYPE, Any::class.java, Long::class.javaPrimitiveType!!, overridable = false)
        method(api, "updateCurrentSourceType", Void.TYPE, Any::class.java, Int::class.javaPrimitiveType!!, overridable = false)
        method(api, "queryCurrentFocusClient", String::class.java, Any::class.java, overridable = false)
    }

    fun register(target: Any, packageName: String, callback: Any): Any? =
        if (linkedSession) registration.invoke(target, packageName, callback, packageName)
        else registration.invoke(target, packageName, callback)

    private fun optional(type: Class<*>, name: String, returns: Class<*>, vararg args: Class<*>): Method? =
        try { method(type, name, returns, *args) } catch (_: NoSuchMethodException) { null }

    private fun method(type: Class<*>, name: String, returns: Class<*>, vararg args: Class<*>, overridable: Boolean = true): Method =
        type.getMethod(name, *args).also {
            require(it.returnType == returns && (!overridable || (!Modifier.isFinal(it.modifiers) && !Modifier.isStatic(it.modifiers)))) {
                "SDK_METHOD_SIGNATURE_MISMATCH_$name"
            }
        }
}
