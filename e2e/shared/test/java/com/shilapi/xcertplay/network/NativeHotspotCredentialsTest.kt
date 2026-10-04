package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import org.junit.Assert.*
import org.junit.Test

class NativeHotspotCredentialsTest {
    @Test fun deviceSuffixIsStableButPasswordIsIndependentAndStrong() {
        val generated = (1..100).map { NativeHotspotCredentials.generate("abc") }
        assertTrue(generated.all { it.ssid == "CarPlay_7F72" && it.valid() })
        assertEquals(100, generated.map { it.password }.toSet().size)
        generated.forEach { value ->
            assertEquals(16, value.password.length)
            assertTrue(value.password.any(Char::isUpperCase))
            assertTrue(value.password.any(Char::isLowerCase))
            assertTrue(value.password.any(Char::isDigit))
            assertFalse(value.toString().contains(value.password))
            assertEquals(ManualHotspotSecurity.WPA2, value.security)
        }
    }

    @Test fun redactedOrMissingPasswordCannotOverwriteSavedCredentials() {
        assertFalse(NativeHotspotCredentials("native", "********", ManualHotspotSecurity.WPA2).valid())
        assertFalse(NativeHotspotCredentials("native", "", ManualHotspotSecurity.WPA2).valid())
        assertFalse(NativeHotspotCredentials("<unknown ssid>", "", ManualHotspotSecurity.OPEN).valid())
        assertFalse(NativeHotspotCredentials("native", "unexpected", ManualHotspotSecurity.OPEN).valid())
        assertTrue(NativeHotspotCredentials("native", "", ManualHotspotSecurity.OPEN).valid())
    }
}
