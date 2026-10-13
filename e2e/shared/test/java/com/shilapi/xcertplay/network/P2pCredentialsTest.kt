package com.shilapi.xcertplay.network

import org.junit.Assert.*
import org.junit.Test

class P2pCredentialsTest {
    @Test fun explicitRequestAcceptsExactOrQuotedReadbackAndUnavailableReadback() {
        for (value in listOf("test12345", "\"test12345\"", null))
            assertEquals("test12345", P2pCredentials.passphrase(value, "test12345"))
        assertEquals("\"test12345\"", P2pCredentials.passphrase("\"test12345\"", "\"test12345\""))
    }
    @Test fun differentCredentialIsRejectedWithoutLeakingEitherValue() {
        val error = assertThrows(WirelessStartupException::class.java) {
            P2pCredentials.passphrase("other-secret", "test12345")
        }
        assertEquals(WirelessStartupFailure.HOTSPOT_CONFIGURATION, error.reason)
        assertFalse(error.message!!.contains("other-secret")); assertFalse(error.message!!.contains("test12345"))
    }
    @Test fun systemGeneratedCredentialCannotBeInventedWhenUnavailable() {
        for (value in listOf(null, "", "short", "x".repeat(64)))
            assertThrows(WirelessStartupException::class.java) { P2pCredentials.passphrase(value, null) }
        assertEquals("system123", P2pCredentials.passphrase("system123", null))
    }
    @Test fun onlyConcreteUnicastInterfaceAddressCanBecomeAnApHint() {
        for (value in listOf(null, "bad", "00:00:00:00:00:00", "02:00:00:00:00:00", "01:11:22:33:44:55"))
            assertNull(P2pCredentials.accessPointHint(value))
        assertArrayEquals(byteArrayOf(2, 17, 34, 51, 68, 85), P2pCredentials.accessPointHint("02:11:22:33:44:55"))
    }
}
