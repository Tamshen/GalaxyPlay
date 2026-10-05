package com.shilapi.xcertplay.network

import android.net.wifi.WifiManager
import android.net.wifi.SoftApConfiguration
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE, shadows = [HotspotWriteShadow::class])
class NativeHotspotConfigurationTest {
    private val events = mutableListOf<String>()
    private val proposed = NativeHotspotCredentials("PrivateNetworkExample", "PrivateExample9", ManualHotspotSecurity.WPA2)
    @Before fun reset() {
        HotspotWriteShadow.accepted = true
        HotspotWriteShadow.denied = false
        HotspotWriteShadow.on = false
        HotspotWriteShadow.readDenied = false
        HotspotWriteShadow.mismatch = false
        HotspotWriteShadow.writes = 0
        HotspotWriteShadow.config = null
    }
    private fun access() = NativeHotspotConfiguration(RuntimeEnvironment.getApplication(), events::add)

    @Test fun acceptedWriteHasIndependentReadbackEvidenceAndNoCredentialsInLog() {
        assertNull(access().apply(proposed))
        assertEquals(1, HotspotWriteShadow.writes)
        assertTrue(events.any { it.contains("accepted=true") })
        assertTrue(events.any { it.contains("readbackMatches=true") })
        assertFalse(events.joinToString().contains("Private"))
    }

    @Test fun acceptedButUnreadableConfigIsNotClaimedAsConfirmedReadback() {
        HotspotWriteShadow.readDenied = true
        assertNull(access().apply(proposed))
        assertTrue(events.any { it.contains("readbackAvailable=false") && it.contains("readbackMatches=null") })
        assertFalse(events.joinToString().contains("Private"))
    }

    @Test fun falseReturnMismatchAndSystemDenialRemainFailures() {
        HotspotWriteShadow.accepted = false
        assertEquals(NativeHotspotProblem.FAILED, access().apply(proposed))
        assertTrue(events.any { it.contains("accepted=false") })
        HotspotWriteShadow.accepted = true
        HotspotWriteShadow.mismatch = true
        assertEquals(NativeHotspotProblem.FAILED, access().apply(proposed))
        assertTrue(events.any { it.contains("readbackMatches=false") })
        HotspotWriteShadow.denied = true
        assertEquals(NativeHotspotProblem.PERMISSION, access().apply(proposed))
        assertTrue(events.any { it.contains("exceptionType=SecurityException") })
        assertFalse(events.joinToString().contains("Private"))
    }

    @Test fun enabledHotspotIsNeverReconfigured() {
        HotspotWriteShadow.on = true
        assertEquals(NativeHotspotProblem.ACTIVE, access().apply(proposed))
        assertEquals(0, HotspotWriteShadow.writes)
    }
}

@Implements(WifiManager::class)
class HotspotWriteShadow {
    @Implementation fun getWifiApState(): Int = if (on) 13 else 11
    @Implementation fun setSoftApConfiguration(value: SoftApConfiguration): Boolean {
        writes++
        if (denied) throw SecurityException("PrivateExample9")
        config = value
        return accepted
    }
    @Implementation fun getSoftApConfiguration(): SoftApConfiguration? {
        if (readDenied) throw SecurityException("PrivateExample9")
        if (!mismatch) return config
        val builder = SoftApConfiguration.Builder()
        builder.javaClass.getMethod("setSsid", String::class.java).invoke(builder, "DifferentPrivateNetwork")
        builder.setPassphrase("DifferentPrivate9", SoftApConfiguration.SECURITY_TYPE_WPA2_PSK)
        return builder.build()
    }
    @Implementation fun getWifiApConfiguration(): android.net.wifi.WifiConfiguration? = null
    companion object {
        var accepted = true
        var denied = false
        var on = false
        var readDenied = false
        var mismatch = false
        var writes = 0
        var config: SoftApConfiguration? = null
    }
}
