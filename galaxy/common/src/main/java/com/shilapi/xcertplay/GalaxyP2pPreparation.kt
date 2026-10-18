package com.shilapi.xcertplay

import android.os.Build
import com.shilapi.xcertplay.network.GalaxyP2pAutoJoin
import com.shilapi.xcertplay.network.WirelessHotspotInfo
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode

/** Wi-Fi Direct 共用自动加入准备；只捕获连接快照，不依赖车型与配置模板。 */
internal object GalaxyP2pPreparation {
    fun forSession(config: CarPlayRuntimeConfig,
                   api: Int = Build.VERSION.SDK_INT): ((WirelessHotspotInfo, () -> Boolean, (String) -> Unit) -> Unit)? {
        if (api != 30 || config.transport != CarPlayTransport.WIRELESS ||
            config.wirelessHotspotMode != WirelessHotspotMode.WIFI_P2P) return null
        return { info, cancelled, log -> GalaxyP2pAutoJoin.prepare(info, cancelled, log) }
    }
}
