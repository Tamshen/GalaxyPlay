package com.shilapi.xcertplay

import android.net.wifi.WifiConfiguration
import android.net.wifi.SoftApConfiguration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.InvocationTargetException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class L7HotspotProbeTest {
    private fun config(password: String = "PrivateExample9") = WifiConfiguration().apply {
        SSID = "PrivateNetworkExample"
        preSharedKey = password
        allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA2_PSK)
    }

    @Test fun denialDoesNotPreventIndependentLegacyReadOrExposeSecrets() {
        val calls = mutableListOf<String>()
        val probe = L7HotspotProbe(30) { method ->
            calls += method
            when (method) {
                "getWifiApState" -> 11
                "getSoftApConfiguration" -> throw InvocationTargetException(SecurityException("PrivateExample9"))
                else -> config()
            }
        }
        val items = L7HotspotProbe.methods.keys.map(probe::inspect)
        assertEquals(L7HotspotProbe.methods.values.toList(), calls)
        assertEquals(listOf("HOTSPOT_STATE_READ", "HOTSPOT_DENIED", "VALID_CONFIG"), items.map { it.reason })
        assertEquals("SecurityException", items[1].facts["exceptionType"])
        assertEquals("false", items[0].facts["hotspotEnabled"])
        items.forEach {
            val json = it.json().toString()
            assertFalse(json.contains("PrivateNetworkExample"))
            assertFalse(json.contains("PrivateExample9"))
        }
    }

    @Test fun maskedEmptyInvalidAndValidConfigurationsRemainDistinct() {
        assertEquals("MASKED_PASSWORD", L7HotspotProbe(30) { config("********") }.inspect("HOTSPOT-LEGACY").reason)
        assertEquals("EMPTY_CONFIG", L7HotspotProbe(30) { null }.inspect("HOTSPOT-LEGACY").reason)
        assertEquals("INVALID_CONFIG", L7HotspotProbe(30) { config("") }.inspect("HOTSPOT-LEGACY").reason)
        assertEquals("VALID_CONFIG", L7HotspotProbe(30) { config() }.inspect("HOTSPOT-LEGACY").reason)
    }

    @Test fun modernConfigurationIsValidatedWithoutRetainingCredentials() {
        val builder = SoftApConfiguration.Builder()
        builder.javaClass.getMethod("setSsid", String::class.java).invoke(builder, "PrivateNetworkExample")
        builder.setPassphrase("PrivateExample9", SoftApConfiguration.SECURITY_TYPE_WPA2_PSK)
        val item = L7HotspotProbe(30) { builder.build() }.inspect("HOTSPOT-CONFIG")
        assertEquals("VALID_CONFIG", item.reason)
        assertEquals("true", item.facts["configValid"])
        assertFalse(item.json().toString().contains("Private"))
    }

    @Test fun failuresAreEvidenceAboutAccessRatherThanFirmwareSupport() {
        val errors = listOf(NoSuchMethodException() to "INTERFACE_NOT_VISIBLE",
            L7HotspotProbe.ServiceUnavailable() to "SERVICE_UNAVAILABLE",
            IllegalStateException("PrivateExample9") to "QUERY_FAILED")
        errors.forEach { (error, reason) ->
            val item = L7HotspotProbe(30) { throw error }.inspect("HOTSPOT-CONFIG")
            assertEquals(reason, item.reason)
            assertNotEquals(L7ProbeStatus.UNSUPPORTED, L7ProbeStatus.of(item))
            assertFalse(item.json().toString().contains("PrivateExample9"))
        }
    }

    @Test fun modernApiIsNotCalledOnAndroid10AndInvalidStateIsNotDisabled() {
        val modern = L7HotspotProbe(29) { fail("不应调用 Android 11 接口") }.inspect("HOTSPOT-CONFIG")
        assertEquals(L7ProbeOutcome.NOT_APPLICABLE, modern.result)
        val invalid = L7HotspotProbe(30) { 99 }.inspect("HOTSPOT-STATE")
        assertEquals("INVALID_RESPONSE", invalid.reason)
        assertNull(invalid.facts["hotspotEnabled"])
    }

    @Test fun enabledStateDoesNotClaimPhoneConnection() {
        val item = L7HotspotProbe(30) { 13 }.inspect("HOTSPOT-STATE")
        assertEquals("true", item.facts["hotspotEnabled"])
        assertFalse(item.facts.containsKey("phoneConnected"))
    }
    @Test @Config(sdk = [29]) fun legacyReadOnAndroid10DoesNotLoadModernConfigurationClass() {
        val item = L7HotspotProbe(29) { config() }.inspect("HOTSPOT-LEGACY")
        assertEquals("VALID_CONFIG", item.reason)
        assertFalse(item.json().toString().contains("Private"))
    }

}
