package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.iap2.wire.Iap2Parameter
import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList
import com.shilapi.xcertplay.mfi.Iap2MfiAuthenticationClient
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class WirelessStartupRefreshTest {
    private val endpoint = Iap2WirelessCarPlayEndpoint("test-ap", "test12345", 0,
        Iap2WirelessSecurity.WPA_WPA2, listOf("192.0.2.1"), 7000, "test-id", "abcd", "1.0")
    private val identification = Iap2IdentificationConfig(name = "test", modelIdentifier = "test",
        manufacturer = "test", serialNumber = "test", firmwareVersion = "1", hardwareVersion = "1",
        wireless = Iap2WirelessIdentification("02:11:22:33:44:55", "test-ap"))

    private fun runLoop(enabled: Boolean, tcpArrives: Boolean = false, withdrawn: Boolean = false,
                        validationFails: Boolean = false, invalidateAtRefresh: Boolean = false,
                        tcpDuringValidation: Boolean = false, availabilityAfterRefresh: Boolean = false,
                        refreshEndpoint: ((Iap2WirelessCarPlayEndpoint) -> Iap2WirelessCarPlayEndpoint?)? = null): List<Iap2Frame> {
        val session = mock(Iap2Session::class.java)
        val mfi = mock(Iap2MfiAuthenticationClient::class.java)
        val sent = mutableListOf<Iap2Frame>()
        var now = 0L
        var waiting = true
        var closed = false
        var receives = 0
        var validations = 0
        val recovery = if (enabled) WirelessStartSessionRecovery({ waiting }, nowNanos = { now * 1_000_000 }) else null
        doAnswer { sent += it.getArgument<Iap2Frame>(0); null }.`when`(session).send(
            any(Iap2Frame::class.java) ?: Iap2Frame(0, byteArrayOf()), anyLong())
        `when`(session.isClosed).thenAnswer { closed }
        `when`(session.recv(anyLong())).thenAnswer {
            when (receives++) {
                0 -> Iap2Frame(0x4300, byteArrayOf())
                1 -> {
                    now += it.getArgument<Long>(0)
                    if (tcpArrives) waiting = false
                    if (withdrawn) Iap2Frame(0x4e0d,
                        Iap2ParameterList.of(Iap2Parameter(0, byteArrayOf(0))).encode()) else null
                }
                2 -> if (availabilityAfterRefresh) Iap2Frame(0x4300, byteArrayOf())
                    else { now += it.getArgument<Long>(0); closed = true; null }
                else -> { now += it.getArgument<Long>(0); closed = true; null }
            }
        }
        mockConstruction(Iap2IdentificationClient::class.java).use {
            val operation = {
                Iap2WirelessControlClient(session, mfi).run(identification, endpoint,
                    startupRecovery = recovery, beforeStartSession = {
                        validations++
                        if (tcpDuringValidation && validations > 1) waiting = false
                        if (validationFails && validations > 1) throw java.io.IOException("interface changed")
                    }, startupRefreshEndpoint = if (invalidateAtRefresh || refreshEndpoint != null) { value ->
                        if (invalidateAtRefresh) waiting = false
                        if (refreshEndpoint == null) value else refreshEndpoint(value)
                    } else null)
            }
            if (validationFails) assertThrows(java.io.IOException::class.java) { operation() }
            else operation()
        }
        return sent
    }

    @Test fun silentPhoneGetsOneIdenticalStartOnTheSameChannelWithoutResendingCredentials() {
        val sent = runLoop(true)
        val starts = sent.filter { it.messageId == 0x4301 }
        assertEquals(2, starts.size)
        assertArrayEquals(starts[0].encodedFrame(), starts[1].encodedFrame())
        assertFalse(sent.any { it.messageId == 0x5703 })
    }

    @Test fun otherBackendsFirstTcpAndCarPlayWithdrawalDoNotRefresh() {
        for (sent in listOf(runLoop(false), runLoop(true, tcpArrives = true), runLoop(true, withdrawn = true)))
            assertEquals(1, sent.count { it.messageId == 0x4301 })
    }

    @Test fun changedHotspotIsRevalidatedBeforeRefreshAndNoStaleRequestIsWritten() {
        assertEquals(1, runLoop(true, validationFails = true).count { it.messageId == 0x4301 })
    }

    @Test fun directRecoveryWritesBoundIpv6OnTheSameAuthenticatedChannel() {
        val ipv4 = java.net.InetAddress.getByName("192.0.2.1")
        val ipv6 = java.net.Inet6Address.getByAddress(null,
            java.net.InetAddress.getByName("fe80::1234").address, 22)
        val addresses = listOf(ipv4, ipv6)
        val info = com.shilapi.xcertplay.network.WirelessHotspotInfo(
            endpoint.ssid, endpoint.passphrase, endpoint.security, 149, 5745, null, "p2p0",
            ipv4, "5 GHz", com.shilapi.xcertplay.network.WirelessHotspotBackend.WIFI_P2P, addresses)
        val policy = com.shilapi.xcertplay.network.GalaxyWirelessAddressRecovery.create(
            mock(android.content.Context::class.java), info, true, { true }, { addresses })!!
        val sent = runLoop(true, refreshEndpoint = policy::refresh)
        val starts = sent.filter { it.messageId == 0x4301 }
        assertEquals(2, starts.size)
        assertArrayEquals(Iap2WirelessControlClient.carPlayStartSession(endpoint).encodedFrame(), starts[0].encodedFrame())
        val alternate = endpoint.withIpAddresses(listOf(ipv6.hostAddress!!.substringBefore('%')))
        assertArrayEquals(Iap2WirelessControlClient.carPlayStartSession(alternate).encodedFrame(), starts[1].encodedFrame())
        assertFalse(sent.any { it.messageId == 0x5703 })
    }

    @Test fun firstTcpDuringCandidateLookupOrValidationRejectsTheRefreshWrite() {
        for (sent in listOf(runLoop(true, invalidateAtRefresh = true),
            runLoop(true, tcpDuringValidation = true)))
            assertEquals(1, sent.count { it.messageId == 0x4301 })
    }

    @Test fun missingCandidateConsumesTheSingleRefreshBudgetWithoutWriting() {
        var requests = 0
        val sent = runLoop(true, refreshEndpoint = { requests++; null })
        assertEquals(1, requests)
        assertEquals(1, sent.count { it.messageId == 0x4301 })
    }

    @Test fun laterPhoneAvailabilityDoesNotRevertTheRecoveredEndpoint() {
        val sent = runLoop(true, availabilityAfterRefresh = true,
            refreshEndpoint = { it.withIpAddresses(listOf("fe80::1234")) })
        val starts = sent.filter { it.messageId == 0x4301 }
        assertEquals(3, starts.size)
        assertArrayEquals(starts[1].encodedFrame(), starts[2].encodedFrame())
        assertFalse(starts[0].encodedFrame().contentEquals(starts[2].encodedFrame()))
    }
}
