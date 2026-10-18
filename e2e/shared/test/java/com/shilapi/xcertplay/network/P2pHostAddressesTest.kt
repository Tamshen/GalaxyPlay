package com.shilapi.xcertplay.network

import java.net.InetAddress
import java.net.Inet6Address
import org.junit.Assert.*
import org.junit.Test

class P2pHostAddressesTest {
    private fun ip(text: String) = InetAddress.getByName(text)
    @Test fun explicitModesFilterBothPrimaryAndAdditionalAddressesAndRequireTheirOwnFamily() {
        val ipv4 = ip("192.168.49.1")
        val ipv6 = Inet6Address.getByAddress(null, ip("fe80::1234").address, 3)
        val all = listOf(ipv4, ipv6)
        val fixed6 = Inet6Address.getByAddress(null, ipv6.address, 7)
        for (primary in all) {
            assertEquals(listOf(ipv4), p2pHostAddresses(primary, all, 7, P2pAddressMode.IPV4_ONLY))
            assertEquals(listOf(fixed6), p2pHostAddresses(primary, all, 7, P2pAddressMode.IPV6_ONLY))
            assertEquals(setOf(ipv4, fixed6), p2pHostAddresses(primary, all, 7, P2pAddressMode.DUAL_STACK).toSet())
        }
        assertThrows(java.io.IOException::class.java) {
            p2pHostAddresses(ipv4, listOf(ipv4), 7, P2pAddressMode.IPV6_ONLY)
        }
        assertThrows(java.io.IOException::class.java) {
            p2pHostAddresses(ip("fe80::1"), emptyList(), 0, P2pAddressMode.IPV6_ONLY)
        }
        assertEquals(P2pAddressMode.IPV4_ONLY, P2pAddressMode.fromStored(null))
        assertEquals(P2pAddressMode.IPV4_ONLY, P2pAddressMode.fromStored("unknown"))
    }
    @Test fun fixedIpv4DoesNotDependOnWhichFamilyArrivesFirstOrFallbackPrimary() {
        val ipv4 = ip("192.168.49.1")
        val ipv6 = Inet6Address.getByAddress(null, ip("fe80::1234").address, 7)
        for (primary in listOf(ipv4, ipv6)) {
            assertEquals(listOf(ipv4), p2pHostAddresses(primary, listOf(ipv6, ipv4), 7, addressMode = com.shilapi.xcertplay.network.P2pAddressMode.IPV4_ONLY))
        }
        assertThrows(java.io.IOException::class.java) {
            p2pHostAddresses(ipv6, listOf(ipv6), 7, addressMode = com.shilapi.xcertplay.network.P2pAddressMode.IPV4_ONLY)
        }
    }
    @Test fun ipv4GroupOwnerKeepsIpv6DiscoveryOnTheActualInterface() {
        val primary = ip("192.168.49.1")
        val wrongScope = Inet6Address.getByAddress(null, ip("fe80::1234").address, 3)
        val hosts = p2pHostAddresses(primary, listOf(wrongScope, primary, ip("2001:db8::1")), 7)
        assertEquals(2, hosts.size)
        assertEquals(primary, hosts.first())
        assertEquals(7, (hosts.last() as Inet6Address).scopeId)
        assertEquals(primary, wirelessHostAddress(hosts, 7))
    }
    @Test fun singleStackAndConnectionInfoFallbackDoNotInventAddresses() {
        val primary = ip("192.168.49.1")
        assertEquals(listOf(primary), p2pHostAddresses(primary, emptyList(), 0))
        assertEquals(listOf(primary), p2pHostAddresses(primary,
            listOf(primary, ip("127.0.0.1"), ip("224.0.0.251"), ip("fe80::1")), 0))
        val scoped = p2pHostAddresses(ip("fe80::1234"), listOf(ip("fe80::1234")), 7)
        assertEquals(1, scoped.size)
        assertEquals(7, (scoped.single() as Inet6Address).scopeId)
    }
}
