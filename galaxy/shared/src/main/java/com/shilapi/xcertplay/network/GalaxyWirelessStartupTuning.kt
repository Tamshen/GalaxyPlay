package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.orchestration.WirelessHotspotMode

/** Direct 首 TCP 最多等待三十秒，保留同链路补发；有线和其他后端沿用原期限。 */
object GalaxyWirelessStartupTuning {
    const val P2P_FIRST_TCP_MILLIS = 30_000L

    fun firstTcpMillis(wireless: Boolean, mode: WirelessHotspotMode): Long =
        if (wireless && mode == WirelessHotspotMode.WIFI_P2P) P2P_FIRST_TCP_MILLIS
        else WirelessStartupPolicy.FIRST_TCP_MILLIS

    fun refreshEnabled(wireless: Boolean, mode: WirelessHotspotMode): Boolean =
        wireless && mode == WirelessHotspotMode.WIFI_P2P
}
