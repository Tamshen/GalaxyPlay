package com.shilapi.xcertplay

import android.content.Context

/** 每个候选包先核对完整音乐契约，避免可见的旧 SDK 阻断其他可用提供包。 */
internal object GalaxyMediaSdk {
    fun load(context: Context, channel: GalaxyMediaReportingChannel.Mode): L7VendorSdk.Loaded =
        GalaxySteeringSetupLog(L7DebugLog.logger(context)).use { diagnostics ->
            diagnostics.step(GalaxySteeringSetupLog.Stage.START, mapOf(
                "generation" to L7SteeringDiagnostics.store.snapshot().generation, "channel" to channel.ordinal.toLong()))
            load(context, channel, diagnostics)
        }

    private fun load(context: Context, channel: GalaxyMediaReportingChannel.Mode, diagnostics: GalaxySteeringSetupLog) =
        L7VendorSdk(context, diagnostics).load(L7ReflectiveMediaCenter.API, channel.sdkPackages) { loader ->
            fun type(name: String): Class<*> {
                diagnostics.step(GalaxySteeringSetupLog.Stage.SDK_CLASS, mapOf("target" to GalaxySteeringSetupLog.target(name)))
                return Class.forName(name, false, loader)
            }
            val api = type(L7ReflectiveMediaCenter.API)
            val client = type(L7ReflectiveMediaCenter.CLIENT)
            val info = type(L7ReflectiveMediaCenter.INFO)
            val callback = type("com.ecarx.eas.sdk.ECarXApiClient\$Callback")
            diagnostics.step(GalaxySteeringSetupLog.Stage.SDK_CONTRACT)
            api.getMethod("get", Context::class.java)
            api.getMethod("init", Context::class.java, callback)
            api.getMethod("release")
            GalaxyOemMediaContract(api, client, info, channel.linkMediaSession)
            if (channel != GalaxyMediaReportingChannel.Mode.CURRENT)
                info.getField(if (channel == GalaxyMediaReportingChannel.Mode.LOCAL) "SOURCE_TYPE_LOCAL" else "SOURCE_TYPE_ONLINE")
        }
}
