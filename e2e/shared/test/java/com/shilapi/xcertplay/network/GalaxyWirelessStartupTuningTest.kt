package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import org.junit.Assert.*
import org.junit.Test

class GalaxyWirelessStartupTuningTest {
    private fun config(timeout: Long = WirelessStartupPolicy.FIRST_TCP_MILLIS) = CarPlayRuntimeConfig(
        mfiTarget = MfiTarget.LOCAL,
        identification = Iap2IdentificationConfig(name = "test", modelIdentifier = "test", manufacturer = "test",
            serialNumber = "test", firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3),
        wirelessFirstTcpTimeoutMillis = timeout,
    )

    @Test fun directKeepsScanningWindowWithoutEnablingRefreshOnUnrelatedBackends() {
        for (mode in WirelessHotspotMode.entries) {
            assertEquals(if (mode == WirelessHotspotMode.WIFI_P2P) 45_000L else 30_000L,
                GalaxyWirelessStartupTuning.firstTcpMillis(true, mode))
            assertEquals(mode == WirelessHotspotMode.WIFI_P2P, GalaxyWirelessStartupTuning.refreshEnabled(true, mode))
            assertEquals(30_000L, GalaxyWirelessStartupTuning.firstTcpMillis(false, mode))
            assertFalse(GalaxyWirelessStartupTuning.refreshEnabled(false, mode))
        }
    }

    @Test fun coreDefaultsRemainThirtySecondsAndRejectUnboundedDeadlines() {
        assertEquals(30_000L, config().wirelessFirstTcpTimeoutMillis)
        assertFalse(config().wirelessStartupRefreshEnabled)
        assertFalse(config().wifiP2pIpv4Only)
        assertEquals(45_000L, config(45_000).wirelessFirstTcpTimeoutMillis)
        for (timeout in listOf(0L, -1L, 60_001L, Long.MAX_VALUE))
            assertThrows(IllegalArgumentException::class.java) { config(timeout) }
    }
}
