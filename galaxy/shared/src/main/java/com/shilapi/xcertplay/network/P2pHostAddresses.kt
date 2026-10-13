package com.shilapi.xcertplay.network

import java.net.InetAddress

/** 主地址仍用于 iAP2；发现与 TCP 同时覆盖本轮 P2P 接口的两种地址族。 */
internal fun p2pHostAddresses(primary: InetAddress, addresses: List<InetAddress>, interfaceIndex: Int): List<InetAddress> {
    val scopedPrimary = wirelessHostAddress(listOf(primary), interfaceIndex) ?: primary
    return (listOf(scopedPrimary) + existingWifiHostAddresses(addresses, interfaceIndex)).distinct()
}
