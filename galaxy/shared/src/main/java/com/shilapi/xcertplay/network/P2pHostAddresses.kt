package com.shilapi.xcertplay.network

import java.net.InetAddress
import java.net.Inet6Address
import java.io.IOException

/** 发现、监听和 iAP2 使用相同地址模式及快照，不依赖地址到达顺序。 */
internal fun p2pHostAddresses(primary: InetAddress, addresses: List<InetAddress>, interfaceIndex: Int,
                              addressMode: P2pAddressMode = P2pAddressMode.DUAL_STACK): List<InetAddress> {
    val scopedPrimary = wirelessHostAddress(listOf(primary), interfaceIndex) ?: primary
    val selected = (listOf(scopedPrimary) + existingWifiHostAddresses(addresses, interfaceIndex)).distinct()
    if (addressMode == P2pAddressMode.DUAL_STACK) return selected
    return selected.filter { addressMode.allows(it) &&
        !it.isAnyLocalAddress && !it.isLoopbackAddress && !it.isMulticastAddress &&
        (it !is Inet6Address || it.isLinkLocalAddress && it.scopeId > 0) }.ifEmpty {
        throw IOException("Wi-Fi Direct has no usable address for ${addressMode.name}")
    }
}
