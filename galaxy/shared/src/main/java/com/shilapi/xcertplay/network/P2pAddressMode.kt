package com.shilapi.xcertplay.network

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** 只筛选实际接口地址；单栈模式不会用另一地址族补位。 */
enum class P2pAddressMode {
    IPV4_ONLY, IPV6_ONLY, DUAL_STACK;

    internal fun allows(address: InetAddress): Boolean = when (this) {
        IPV4_ONLY -> address is Inet4Address
        IPV6_ONLY -> address is Inet6Address
        DUAL_STACK -> true
    }

    companion object {
        fun fromStored(value: String?): P2pAddressMode = entries.firstOrNull { it.name == value } ?: IPV4_ONLY
    }
}
