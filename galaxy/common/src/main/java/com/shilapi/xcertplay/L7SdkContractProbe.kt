package com.shilapi.xcertplay

import android.content.Context

/** 仅读取类和方法签名；禁止 get/init/create/register，以及类静态初始化。 */
internal object L7SdkContractProbe {
    fun inspect(context: Context): Map<String, String?> {
        val result = linkedMapOf<String, String?>("effectiveCall" to "CLASS_SIGNATURE_AND_PACKAGE_QUERY_ONLY")
        result.putAll(L7VendorServiceProbe.inspect(context))
        val channel = GalaxyMediaReportingChannel.load(context)
        result["mediaReportingChannel"] = channel.name
        check(context, L7ReflectiveMediaCenter.API, "media", result) { type, loader ->
            val client = Class.forName(L7ReflectiveMediaCenter.CLIENT, false, loader)
            val info = Class.forName(L7ReflectiveMediaCenter.INFO, false, loader)
            val callback = Class.forName("com.ecarx.eas.sdk.ECarXApiClient\$Callback", false, loader)
            type.getMethod("get", Context::class.java)
            type.getMethod("init", Context::class.java, callback)
            val contract = GalaxyOemMediaContract(type, client, info, channel.linkMediaSession)
            result["mediaRegistration"] = if (contract.linkedSession)
                "OWN_PACKAGE_MEDIASESSION_LINK_AVAILABLE" else "OWN_PACKAGE_ONLY"
            if (channel == GalaxyMediaReportingChannel.Mode.LOCAL) info.getField("SOURCE_TYPE_LOCAL")
            else if (channel != GalaxyMediaReportingChannel.Mode.CURRENT || result["mediaProviderEasSupport"] == "1")
                info.getField("SOURCE_TYPE_ONLINE")
            result["mediaPlaybackContract"] = "APP_ICON_LAUNCH_PLAYER_INTENT_AND_SUPPORTED_CAPABILITIES"
            result["mediaCustomAction"] = if (contract.clientMethods.any { it.name == "onCustomAction" }) "VISIBLE" else "NOT_IN_THIS_SDK"
            result["mediaVehicleContract"] = "L6_L7_MATCHING_OEM_PUBLIC_SDK"
            result["mediaCallbackKind"] = if (client.isInterface) "INTERFACE" else "CLASS"
            result["mediaSourceEvidence"] = when {
                channel == GalaxyMediaReportingChannel.Mode.LOCAL -> "OEM_LOCAL_0_DISPLAY_UNTESTED"
                channel != GalaxyMediaReportingChannel.Mode.CURRENT || result["mediaProviderEasSupport"] == "1" ->
                    "L6_L7_ONLINE_6_DISPLAY_UNTESTED"
                else -> "CARPLAY_REFERENCE_13_ACCEPTANCE_UNTESTED"
            }
        }
        check(context, L7ReflectiveNavigation.API, "navigation", result) { type, loader ->
            Class.forName("ecarx.fw.api.ECarXAPI", false, loader).getMethod("creator", Class::class.java)
            Class.forName("ecarx.fw.api.ICreator", false, loader).getMethod("create", Context::class.java)
            val callback = type.methods.single { it.name == "registerNavigationInteractionCallback" && it.parameterCount == 1 }.parameterTypes.single()
            type.getMethod("unregisterNavigationInteractionCallback", callback)
            type.getMethod("notifyTurnByTurnStarted")
            type.getMethod("notifyTurnByTurnStopped")
            type.getMethod("updateNextGuidancePointName", String::class.java)
            val instance = Class.forName("com.autolink.adaptersrv.diminteraction.EcarxNaviInstance", false, loader)
            instance.getMethod("getInstance", Context::class.java)
            instance.getMethod("getService")
            result["navigationMapping"] = "START_STOP_ROAD_ONLY_OTHER_UNITS_UNCONFIRMED"
        }
        return result
    }
    private fun check(context: Context, name: String, key: String, result: MutableMap<String, String?>,
                      verify: (Class<*>, ClassLoader) -> Unit) {
        try {
            val loaded = if (key == "media") GalaxyMediaSdk.load(context, GalaxyMediaReportingChannel.load(context))
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
