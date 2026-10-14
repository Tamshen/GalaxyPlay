package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.transport.Iap2WirelessCarPlayEndpoint
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.Inet6Address

class GalaxyHotspotAddressRecoveryTest {
    private val ipv4 = InetAddress.getByName("192.168.43.1")
    private val raw6 = InetAddress.getByName("fe80::1234") as Inet6Address
    private val ipv6 = Inet6Address.getByAddress(null, raw6.address, 22)
    private var live = listOf(ipv4, ipv6)
    private var current = true
    private val remembered = mutableListOf<String>()
    private val logs = mutableListOf<String>()
    private val endpoint = Iap2WirelessCarPlayEndpoint("test-ap", "test12345", 0,
        Iap2WirelessSecurity.WPA_WPA2, listOf("192.168.43.1"), 7003, "test-id", "abcd", "1.0",
        byteArrayOf(2, 1, 2, 3, 4, 5))

    private fun recovery(addresses: List<InetAddress> = live, preference: String? = null,
                         sample: () -> List<InetAddress> = { live }) =
        GalaxyHotspotAddressRecovery(addresses, preference, sample, { current }, remembered::add, logs::add)

    @Test fun dualStackRefreshChangesOnlyTheStartSessionAddressAndIsBounded() {
        val policy = recovery()
        assertEquals(ipv4, policy.initialAddress)
        val retry = policy.refresh(endpoint)!!
        assertEquals(listOf(ipv6.hostAddress!!.substringBefore('%')), retry.ipAddresses)
        assertEquals(endpoint.ssid, retry.ssid)
        assertEquals(endpoint.passphrase, retry.passphrase)
        assertEquals(endpoint.security, retry.security)
        assertEquals(endpoint.channel, retry.channel)
        assertEquals(endpoint.airPlayPort, retry.airPlayPort)
        assertEquals(endpoint.deviceIdentifier, retry.deviceIdentifier)
        assertEquals(endpoint.publicKey, retry.publicKey)
        assertEquals(endpoint.sourceVersion, retry.sourceVersion)
        assertArrayEquals(endpoint.accessPointBssid, retry.accessPointBssid)
        assertEquals(listOf("192.168.43.1"), endpoint.ipAddresses)
        assertNull(policy.refresh(endpoint))
        assertTrue(remembered.isEmpty())
    }

    @Test fun renderedIpv6IsRememberedAndPreferredOnTheNextAttempt() {
        val policy = recovery()
        policy.refresh(endpoint)
        assertTrue(remembered.isEmpty())
        policy.rendered(ipv6)
        policy.rendered(ipv4)
        assertEquals(listOf("IPv6"), remembered)
        val next = recovery(preference = remembered.single())
        assertEquals(ipv6, next.initialAddress)
        assertEquals(listOf("192.168.43.1"), next.refresh(endpoint.withIpAddresses(listOf("fe80::1234")))!!.ipAddresses)
    }

    @Test fun actualRenderedFamilyWinsEvenIfPhoneUsesTheOtherListener() {
        val policy = recovery()
        policy.refresh(endpoint)
        policy.rendered(ipv4)
        assertEquals(listOf("IPv4"), remembered)
    }

    @Test fun socketWithoutIpv6ScopeStillIdentifiesTheAlreadyBoundInterface() {
        recovery().rendered(raw6)
        assertEquals(listOf("IPv6"), remembered)
    }

    @Test fun singleStackOrMissingPreferredFamilyUsesTheOriginalAddress() {
        live = listOf(ipv4)
        val policy = recovery(preference = "IPv6")
        assertEquals(ipv4, policy.initialAddress)
        assertEquals(endpoint.ipAddresses, policy.refresh(endpoint)!!.ipAddresses)
    }

    @Test fun lateUnboundIpv6CannotBePublishedAndRemovedAddressesCannotBeRetried() {
        val policy = recovery(addresses = listOf(ipv4))
        assertEquals(endpoint.ipAddresses, policy.refresh(endpoint)!!.ipAddresses)
        val removed = recovery()
        live = emptyList()
        assertNull(removed.refresh(endpoint))
    }

    @Test fun differentInterfaceScopeAndInvalidAddressesAreRejected() {
        val wrongScope = Inet6Address.getByAddress(null, raw6.address, 23)
        val policy = recovery(addresses = listOf(ipv4, raw6, InetAddress.getByName("::1"), ipv6))
        assertEquals(listOf(ipv4, ipv6), policy.publishedAddresses)
        live = listOf(ipv4, wrongScope)
        assertEquals(endpoint.ipAddresses, policy.refresh(endpoint)!!.ipAddresses)
        policy.rendered(wrongScope)
        policy.rendered(InetAddress.getByName("192.168.43.2"))
        assertTrue(remembered.isEmpty())
    }

    @Test fun cancellationDuringLookupCannotSendOrRememberAnOldRun() {
        val policy = recovery(sample = { current = false; live })
        assertNull(policy.refresh(endpoint))
        policy.rendered(ipv4)
        assertTrue(remembered.isEmpty())
    }

    @Test fun explicitCloseRejectsLateRefreshAndRenderedCallbacks() {
        val policy = recovery()
        policy.close()
        assertNull(policy.refresh(endpoint))
        policy.rendered(ipv6)
        assertTrue(remembered.isEmpty())
    }

    @Test fun addressLookupFailureDoesNotPublishUnknownEndpoints() {
        val policy = recovery(sample = { throw java.net.SocketException("unavailable") })
        assertNull(policy.refresh(endpoint))
        assertFalse(logs.any { it.contains("test-ap") || it.contains("192.168") || it.contains("fe80:") })
    }

    @Test fun runtimeTunnelUsesTheActualSessionEndpointWithoutTeachingUnrenderedSuccess() {
        val policy = recovery()
        assertEquals(listOf(ipv6.hostAddress!!.substringBefore('%')), policy.forSession(endpoint, raw6).ipAddresses)
        assertEquals(endpoint.ipAddresses, policy.forSession(endpoint, ipv4).ipAddresses)
        assertSame(endpoint, policy.forSession(endpoint, InetAddress.getByName("192.168.43.2")))
        assertTrue(remembered.isEmpty())
        policy.close()
        assertSame(endpoint, policy.forSession(endpoint, ipv6))
    }

    @Test fun diagnosticOrPreferenceFailureCannotBreakStartupOrRendering() {
        val policy = GalaxyHotspotAddressRecovery(live, null, { live }, { true },
            { throw IllegalStateException("storage unavailable") }, { throw IllegalStateException("sink unavailable") })
        assertNotNull(policy.refresh(endpoint))
        policy.rendered(ipv6)
    }
}
