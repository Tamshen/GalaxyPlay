package com.shilapi.xcertplay.network

import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30], manifest = Config.NONE, shadows = [NativeHotspotReadShadow::class])
class NativeHotspotReadTest {
    private val logs = mutableListOf<String>()
    private fun access() = NativeHotspotConfiguration(RuntimeEnvironment.getApplication(), logs::add).apply {
        shellRead = { _, _ -> NativeHotspotRead(problem = NativeHotspotProblem.UNSUPPORTED) }
    }
    private fun legacy(password: String = "PrivatePassword9") = WifiConfiguration().apply {
        SSID = "\"PrivateNetwork\""
        preSharedKey = "\"$password\""
        allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA2_PSK)
    }

    @Before fun reset() {
        NativeHotspotReadShadow.legacy = null
        NativeHotspotReadShadow.modern = null
        NativeHotspotReadShadow.denyLegacy = false
        NativeHotspotReadShadow.denyModern = false
        NativeHotspotReadShadow.calls.clear()
    }

    @Test fun legacyFirstReadImportsQuotedNamePasswordAndSecurityWithoutLoggingThem() {
        NativeHotspotReadShadow.legacy = legacy()
        val result = access().read().credentials!!
        assertEquals("PrivateNetwork", result.ssid)
        assertEquals("PrivatePassword9", result.password)
        assertEquals(ManualHotspotSecurity.WPA2, result.security)
        assertEquals(listOf("legacy"), NativeHotspotReadShadow.calls)
        assertTrue(logs.single().contains("method=getWifiApConfiguration readable=true"))
        assertFalse(logs.joinToString().contains("Private"))
    }

    @Test fun maskedAndUnknownLegacyValuesAreRejected() {
        for (password in listOf("********", "••••••••", "●●●●●●●●", "........", "*•●.*•●.")) {
            NativeHotspotReadShadow.legacy = legacy(password)
            assertEquals(NativeHotspotProblem.INVALID, access().read().problem)
        }
        NativeHotspotReadShadow.legacy = legacy().apply { SSID = "<unknown ssid>" }
        assertNull(access().read().credentials)
        assertFalse(logs.joinToString().contains("Private"))
    }

    @Test fun openLegacyNetworkNeedsAnExplicitOpenSecurityTypeAndEmptyPassword() {
        NativeHotspotReadShadow.legacy = legacy().apply {
            allowedKeyManagement.clear()
            allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
            preSharedKey = null
        }
        val value = access().read().credentials!!
        assertEquals(ManualHotspotSecurity.OPEN, value.security)
        assertEquals("", value.password)
    }

    @Test fun deniedLegacyReadReportsOnlyTheExceptionType() {
        NativeHotspotReadShadow.denyLegacy = true
        NativeHotspotReadShadow.denyModern = true
        assertEquals(NativeHotspotProblem.PERMISSION, access().read().problem)
        assertTrue(logs.any { it.contains("exceptionType=SecurityException") })
        assertFalse(logs.joinToString().contains("Private"))
    }

    @Test fun diagnosticFailureDoesNotTurnAReadableLegacyConfigurationIntoFailure() {
        NativeHotspotReadShadow.legacy = legacy()
        val result = NativeHotspotConfiguration(RuntimeEnvironment.getApplication()) { error("log failed") }.read()
        assertNotNull(result.credentials)
    }

    @Test @Config(sdk = [29]) fun androidTenDoesNotAttemptTheModernGetter() {
        assertEquals(NativeHotspotProblem.INVALID, access().read().problem)
        assertEquals(listOf("legacy"), NativeHotspotReadShadow.calls)
    }

    @Test @Config(sdk = [30]) fun invalidOrDeniedLegacyReadFallsBackToModernConfiguration() {
        val builder = SoftApConfiguration.Builder()
        builder.javaClass.getMethod("setSsid", String::class.java).invoke(builder, "PrivateModern")
        builder.setPassphrase("PrivateModern9", SoftApConfiguration.SECURITY_TYPE_WPA2_PSK)
        NativeHotspotReadShadow.modern = builder.build()
        NativeHotspotReadShadow.legacy = legacy("********")
        assertEquals("PrivateModern9", access().read().credentials!!.password)
        assertEquals(listOf("legacy", "modern"), NativeHotspotReadShadow.calls)
        NativeHotspotReadShadow.denyLegacy = true
        assertEquals("PrivateModern", access().read().credentials!!.ssid)
        assertFalse(logs.joinToString().contains("Private"))
    }
}

@Implements(WifiManager::class)
class NativeHotspotReadShadow {
    @Implementation fun getWifiApConfiguration(): WifiConfiguration? {
        calls += "legacy"
        if (denyLegacy) throw SecurityException("PrivatePassword9")
        return legacy
    }
    @Implementation fun getSoftApConfiguration(): SoftApConfiguration? {
        calls += "modern"
        if (denyModern) throw SecurityException("PrivatePassword9")
        return modern
    }
    companion object {
        var legacy: WifiConfiguration? = null
        var modern: SoftApConfiguration? = null
        var denyLegacy = false
        var denyModern = false
        val calls = mutableListOf<String>()
    }
}
