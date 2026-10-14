package com.shilapi.xcertplay.network

import android.content.Context
import java.security.MessageDigest

/** 仅保留当前热点的成功地址族；网络范围用摘要绑定，不存储热点名称或凭据。 */
internal class GalaxyHotspotAddressMemory(context: Context, info: WirelessHotspotInfo) {
    private val preferences = context.getSharedPreferences("galaxy_hotspot_address", Context.MODE_PRIVATE)
    private val key = MessageDigest.getInstance("SHA-256").digest(
        "${info.interfaceName}\u0000${info.ssid}\u0000${info.security}".toByteArray(Charsets.UTF_8),
    ).joinToString("") { "%02x".format(it) }

    fun preferredFamily(): String? = if (preferences.getString("network", null) == key)
        HotspotAddressPolicy.parseFamily(preferences.getString("family", null)) else null

    fun remember(family: String) {
        if (HotspotAddressPolicy.parseFamily(family) == null) return
        preferences.edit().putString("network", key).putString("family", family).apply()
    }
}
