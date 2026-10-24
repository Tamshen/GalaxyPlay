package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.network.GalaxyP2pRegion
import com.shilapi.xcertplay.network.WifiP2pChannels

/** 地区为应用配置，不读取定位、不改变系统国家码，旧手动信道不静默覆盖。 */
internal object GalaxyP2pSettings {
    private const val KEY = "galaxy_p2p_region"
    private const val KEY_CHANNEL_CHOSEN = "galaxy_p2p_channel_chosen"
    private const val PREFS = "xcertplay_airplay"
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun fixedChannel(context: Context): Int? = com.shilapi.xcertplay.network.L7WifiDirectPolicy
        .fixedChannel(L7AudioTemplates.model(context).id, load(context))

    /** 用户是否在设置里显式选过频道（含“自动”）；没选过才把车型策略频道当默认值。 */
    fun channelChosen(context: Context): Boolean = prefs(context).getBoolean(KEY_CHANNEL_CHOSEN, false)

    /** 用户在界面上保存频道；显式选择“自动”也要记住，不能被车型默认频道覆盖。 */
    fun saveChannel(context: Context, channel: Int) {
        AirPlayPersistence.saveWifiP2pPreferredChannel(context, channel)
        prefs(context).edit().putBoolean(KEY_CHANNEL_CHOSEN, true).apply()
    }

    fun effectiveChannel(context: Context): Int {
        val stored = AirPlayPersistence.loadWifiP2pPreferredChannel(context)
        // 显式保存过（含“自动”），或已存着具体频道（旧版本存的值）→ 都以用户选择为准；
        // 只有从未选过（AUTO 且没有标记）时才用车型策略的默认频道。
        if (channelChosen(context) || stored != WifiP2pChannels.AUTO) return stored
        return fixedChannel(context) ?: stored
    }

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
