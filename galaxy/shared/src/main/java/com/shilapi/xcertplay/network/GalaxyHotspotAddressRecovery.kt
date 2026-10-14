package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.transport.Iap2WirelessCarPlayEndpoint
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** 原生热点只在本轮已监听、仍有效的地址之间恢复，不更换热点或接收端身份。 */
internal class GalaxyHotspotAddressRecovery(
    addresses: List<InetAddress>,
    preferredFamily: String?,
    private val sample: () -> List<InetAddress>,
    private val isCurrent: () -> Boolean,
    private val remember: (String) -> Unit,
    private val log: (String) -> Unit = {},
) {
    val publishedAddresses = addresses.filter(::usable).distinct()
    val initialAddress: InetAddress = HotspotAddressPolicy.select(publishedAddresses, preferredFamily)
        ?: throw IllegalArgumentException("No usable hotspot address")
    private var refreshed = false
    private var confirmed = false
    private var closed = false

    init {
        diagnostic("wireless address selected family=${family(initialAddress)} " +
            "preferred=${preferredFamily ?: "default"} dualStack=${publishedAddresses.any { family(it) != family(initialAddress) }}")
    }

    @Synchronized fun refresh(endpoint: Iap2WirelessCarPlayEndpoint): Iap2WirelessCarPlayEndpoint? {
        if (closed || refreshed || !isCurrent()) return null
        refreshed = true
        val live = runCatching(sample).getOrDefault(emptyList())
        if (closed || !isCurrent()) return null
        // 晚到且没有绑定监听的地址不能通过 iAP2 发布；接口变动继续由宿主预检拒绝。
        val available = publishedAddresses.filter { bound -> live.any { sameAddress(bound, it) } }
        val alternate = HotspotAddressPolicy.alternateFamilyCandidate(available, initialAddress)
        val selected = alternate ?: available.firstOrNull { sameAddress(it, initialAddress) } ?: return null
        diagnostic("wireless address refresh from=${family(initialAddress)} to=${family(selected)} " +
            "changed=${alternate != null}")
        return endpoint.withIpAddresses(listOf(selected.hostAddress!!.substringBefore('%')))
    }

    /** 记录实际出画面的会话本地地址；配置发送、TCP 接入和失败尝试都不能写成功偏好。 */
    @Synchronized fun rendered(localAddress: InetAddress?) {
        if (closed || confirmed || !isCurrent()) return
        val actual = publishedAddress(localAddress) ?: return
        confirmed = true
        val saved = runCatching { remember(family(actual)) }.isSuccess
        diagnostic("wireless address confirmed family=${family(actual)} evidence=rendered-session preferenceSaved=$saved")
    }

    /** 运行隧道沿用实际会话的地址，不重新退回首次无响应的端点。 */
    @Synchronized fun forSession(endpoint: Iap2WirelessCarPlayEndpoint, localAddress: InetAddress?): Iap2WirelessCarPlayEndpoint {
        if (closed || !isCurrent()) return endpoint
        val actual = publishedAddress(localAddress) ?: return endpoint
        return endpoint.withIpAddresses(listOf(actual.hostAddress!!.substringBefore('%')))
    }

    @Synchronized fun close() { closed = true }

    private fun publishedAddress(address: InetAddress?): InetAddress? = publishedAddresses.firstOrNull { bound ->
        address != null && bound.address.contentEquals(address.address) &&
            (address !is Inet6Address || address.scopeId == 0 || sameAddress(bound, address))
    }

    private fun diagnostic(message: String) { runCatching { log(message) } }

    private fun sameAddress(first: InetAddress, second: InetAddress): Boolean =
        first.address.contentEquals(second.address) &&
            (first as? Inet6Address)?.scopeId == (second as? Inet6Address)?.scopeId

    private fun usable(address: InetAddress): Boolean = when (address) {
        is Inet4Address -> !address.isAnyLocalAddress && !address.isLoopbackAddress &&
            !address.isLinkLocalAddress && !address.isMulticastAddress
        is Inet6Address -> address.isLinkLocalAddress && address.scopeId > 0
        else -> false
    }

    private fun family(address: InetAddress): String =
        if (address is Inet6Address) HotspotAddressPolicy.FAMILY_IPV6 else HotspotAddressPolicy.FAMILY_IPV4
}
