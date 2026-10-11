package com.shilapi.xcertplay

import android.content.Context

/** 每个候选包先核对完整音乐契约，避免可见的旧 SDK 阻断其他可用提供包。 */
internal object GalaxyMediaSdk {
    fun load(context: Context, channel: GalaxyMediaReportingChannel.Mode): L7VendorSdk.Loaded =
        L7VendorSdk(context).load(L7ReflectiveMediaCenter.API, channel.sdkPackages) { loader ->
            val api = Class.forName(L7ReflectiveMediaCenter.API, false, loader)
            val client = Class.forName(L7ReflectiveMediaCenter.CLIENT, false, loader)
            val info = Class.forName(L7ReflectiveMediaCenter.INFO, false, loader)
            val callback = Class.forName("com.ecarx.eas.sdk.ECarXApiClient\$Callback", false, loader)
            api.getMethod("get", Context::class.java)
            api.getMethod("init", Context::class.java, callback)
            api.getMethod("release")
            GalaxyOemMediaContract(api, client, info, channel.linkMediaSession)
            if (channel != GalaxyMediaReportingChannel.Mode.CURRENT)
                info.getField(if (channel == GalaxyMediaReportingChannel.Mode.LOCAL) "SOURCE_TYPE_LOCAL" else "SOURCE_TYPE_ONLINE")
        }
}
