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
                        validationFails: Boolean = false): List<Iap2Frame> {
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
                else -> { now += it.getArgument<Long>(0); closed = true; null }
            }
        }
        mockConstruction(Iap2IdentificationClient::class.java).use {
            val operation = {
                Iap2WirelessControlClient(session, mfi).run(identification, endpoint,
                    startupRecovery = recovery, beforeStartSession = {
                        validations++
                        if (validationFails && validations > 1) throw java.io.IOException("interface changed")
                    })
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
}
