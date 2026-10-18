package com.shilapi.xcertplay.network

import java.net.InetAddress
import java.net.Inet4Address
import java.io.IOException

/** 发现、监听和 iAP2 使用同一地址快照；L7 固定 IPv4，不受 IPv6 到达时序影响。 */
internal fun p2pHostAddresses(primary: InetAddress, addresses: List<InetAddress>, interfaceIndex: Int,
                              ipv4Only: Boolean = false): List<InetAddress> {
    val scopedPrimary = wirelessHostAddress(listOf(primary), interfaceIndex) ?: primary
    val selected = (listOf(scopedPrimary) + existingWifiHostAddresses(addresses, interfaceIndex)).distinct()
    if (!ipv4Only) return selected
    return selected.filterIsInstance<Inet4Address>().ifEmpty {
        throw IOException("Wi-Fi Direct has no usable IPv4 address")
    }
}
