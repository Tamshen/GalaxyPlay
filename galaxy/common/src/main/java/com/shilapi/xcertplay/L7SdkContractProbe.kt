package com.shilapi.xcertplay

import android.content.Context

/** 仅读取类和方法签名；禁止 get/init/create/register，以及类静态初始化。 */
internal object L7SdkContractProbe {
    fun inspect(context: Context): Map<String, String?> {
        val result = linkedMapOf<String, String?>("effectiveCall" to "CLASS_SIGNATURE_AND_PACKAGE_QUERY_ONLY")
        result.putAll(L7VendorServiceProbe.inspect(context))
        result["mediaReportingEnabled"] = GalaxyVehiclePreferences.mediaReporting(context).toString()
        check(context, L7ReflectiveMediaCenter.API, "media", result) { type, loader ->
            val client = Class.forName(L7ReflectiveMediaCenter.CLIENT, false, loader)
            val info = Class.forName(L7ReflectiveMediaCenter.INFO, false, loader)
            val callback = Class.forName("com.ecarx.eas.sdk.ECarXApiClient\$Callback", false, loader)
            type.getMethod("get", Context::class.java)
            type.getMethod("init", Context::class.java, callback)
            val contract = GalaxyOemMediaContract(type, client, info)
            result["mediaRegistration"] = if (contract.linkedSession)
                "OWN_PACKAGE_MEDIASESSION_LINK_AVAILABLE" else "OWN_PACKAGE_ONLY"
            if (result["mediaProviderEasSupport"] == "1")
                info.getField("SOURCE_TYPE_ONLINE")
            result["mediaPlaybackContract"] = "APP_ICON_LAUNCH_PLAYER_INTENT_AND_SUPPORTED_CAPABILITIES"
            result["mediaCustomAction"] = if (contract.clientMethods.any { it.name == "onCustomAction" }) "VISIBLE" else "NOT_IN_THIS_SDK"
            result["mediaVehicleContract"] = "L6_L7_MATCHING_OEM_PUBLIC_SDK"
            result["mediaCallbackKind"] = if (client.isInterface) "INTERFACE" else "CLASS"
            result["mediaSourceEvidence"] = when {
                result["mediaProviderEasSupport"] == "1" ->
                    "L6_L7_ONLINE_6_DISPLAY_UNTESTED"
                else -> "CARPLAY_REFERENCE_13_ACCEPTANCE_UNTESTED"
            }
        }
        result["navigationSdkSource"] = "ANDROID_CONTEXT"
        result["navigationContract"] = "AMAP_STANDARD_BROADCAST"
        result["navigationMapping"] = "10001_GUIDANCE_10019_START8_STOP9_RECEPTION_UNVERIFIED"
        return result
    }
    private fun check(context: Context, name: String, key: String, result: MutableMap<String, String?>,
                      verify: (Class<*>, ClassLoader) -> Unit) {
        try {
            val loaded = if (key == "media") GalaxyMediaSdk.load(context)
                else L7VendorSdk(context).load(name)
            result["${key}SdkSource"] = loaded.source
            verify(Class.forName(name, false, loaded.loader), loaded.loader)
            result["${key}Contract"] = "VISIBLE_MATCH"
        } catch (error: Throwable) {
            if (error !is Exception && error !is LinkageError) throw error
            result["${key}Contract"] = when (error) {
                is ClassNotFoundException -> "NOT_VISIBLE_OR_UNINSTALLED"
                is NoSuchMethodException, is NoSuchFieldException, is IllegalArgumentException, is IllegalStateException -> "SIGNATURE_MISMATCH"
                is SecurityException -> "QUERY_DENIED"
                else -> "QUERY_FAILED"
            }
            result["${key}ExceptionType"] = error.javaClass.simpleName
        }
    }
}
