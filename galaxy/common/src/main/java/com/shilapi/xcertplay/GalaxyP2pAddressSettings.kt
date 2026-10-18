package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.network.P2pAddressMode

/** 作为车型草稿及会话快照的一部分读取，缺省与旧配置均使用 IPv4。 */
internal object GalaxyP2pAddressSettings {
    const val KEY = "galaxy_p2p_address_mode"
    fun load(context: Context): P2pAddressMode = P2pAddressMode.fromStored(
        context.getSharedPreferences("xcertplay_airplay", 0).all[KEY] as? String)
}
