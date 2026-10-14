package com.shilapi.xcertplay.network

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

internal object HotspotAddressPolicy {
    const val FAMILY_IPV4 = "IPv4"
    const val FAMILY_IPV6 = "IPv6"

    fun parseFamily(raw: String?): String? = when (raw) {
        FAMILY_IPV4 -> FAMILY_IPV4
        FAMILY_IPV6 -> FAMILY_IPV6
        else -> null
    }

    /** 根据已验证偏好选择；没有可用 IPv6 时保留原 IPv4 优先行为。 */
    fun select(addresses: List<InetAddress>, preferredFamily: String?): InetAddress? {
        if (preferredFamily == FAMILY_IPV6) {
            addresses.filterIsInstance<Inet6Address>().firstOrNull {
                it.isLinkLocalAddress && it.scopeId > 0
            }?.let { return it }
        }
        return select(addresses)
    }

    /** 与上游地址恢复保持相同方向，但拒绝没有本机作用域的链路本地 IPv6。 */
    fun alternateFamilyCandidate(addresses: List<InetAddress>, published: InetAddress): InetAddress? =
        if (published is Inet6Address) addresses.filterIsInstance<Inet4Address>().firstOrNull(::usable)
        else addresses.filterIsInstance<Inet6Address>().firstOrNull { it.isLinkLocalAddress && it.scopeId > 0 }

    fun select(addresses: List<InetAddress>): InetAddress? {
        var linkLocal: InetAddress? = null
        for (address in addresses) {
            if (address is Inet4Address && usable(address)) {
                return address
            }
            if (address is Inet6Address && address.isLinkLocalAddress && linkLocal == null) {
                linkLocal = address
            }
        }
        return linkLocal
    }

    private fun usable(address: InetAddress): Boolean =
        !address.isLoopbackAddress && !address.isAnyLocalAddress && !address.isLinkLocalAddress &&
            !address.isMulticastAddress
}
