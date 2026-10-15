package com.shilapi.xcertplay.network

import android.content.Context
import java.net.InetAddress
import java.net.NetworkInterface

/** Direct 在已监听的本轮地址间补发；原生热点继续保留按网络隔离的成功偏好。 */
internal object GalaxyWirelessAddressRecovery {
    fun create(
        context: Context,
        info: WirelessHotspotInfo,
        directEnabled: Boolean,
        isCurrent: () -> Boolean,
        sample: () -> List<InetAddress> = {
            info.interfaceName?.let { NetworkInterface.getByName(it)?.inetAddresses?.toList() }.orEmpty()
        },
        log: (String) -> Unit = {},
    ): GalaxyHotspotAddressRecovery? {
        val manual = info.backend == WirelessHotspotBackend.MANUAL_HOTSPOT
        if (!manual && !(info.backend == WirelessHotspotBackend.WIFI_P2P && directEnabled)) return null
        val memory = if (manual) GalaxyHotspotAddressMemory(context, info) else null
        val recovery = GalaxyHotspotAddressRecovery(
            info.hostAddresses, memory?.preferredFamily(), sample, isCurrent,
            remember = { memory?.remember(it) }, log = log,
        )
        runCatching { log("wireless address recovery backend=${info.backend.name}") }
        return recovery
    }
}
