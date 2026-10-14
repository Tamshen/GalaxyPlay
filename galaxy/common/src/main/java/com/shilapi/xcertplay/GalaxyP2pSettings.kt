package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.network.GalaxyP2pRegion

/** 地区为应用配置，不读取定位、不改变系统国家码，旧手动信道不静默覆盖。 */
internal object GalaxyP2pSettings {
    private const val KEY = "galaxy_p2p_region"
    fun load(context: Context): GalaxyP2pRegion {
        val prefs = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
        if (!prefs.contains(KEY)) return GalaxyP2pRegion.MAINLAND_CHINA
        val value = runCatching { prefs.getString(KEY, "") }.getOrDefault("")
        return GalaxyP2pRegion.fromStored(value ?: "")
    }
    fun save(context: Context, region: GalaxyP2pRegion) {
        context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit()
            .putString(KEY, region.name).apply()
    }
}
