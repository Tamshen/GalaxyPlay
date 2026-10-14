package com.shilapi.xcertplay.network

import android.net.wifi.p2p.WifiP2pManager
import org.junit.Assert.*
import org.junit.Test

class GalaxyP2pRegionTest {
    private val mainland = GalaxyP2pRegion.MAINLAND_CHINA.policy
    private fun plan(station: Int? = null, remembered: P2pCreationRequest? = null) =
        P2pStartupRecovery.plan(station, remembered, channelPolicy = mainland)

    @Test fun mainlandAutoPrefers149AndNeverUsesIndoorOrUnpinnedChannels() {
        assertEquals(5745, plan().first().frequencyMHz)
        for (station in listOf(null, 2412, 5180, 5200, 5745, 5805, 5500)) {
            for (remembered in listOf(null, P2pCreationRequest(P2pCreationMode.SYSTEM_DEFAULT),
                P2pCreationRequest(P2pCreationMode.FIXED_5_GHZ, 5180))) {
                val plan = plan(station, remembered)
                assertTrue(plan.isNotEmpty())
                assertTrue(plan.all { it.frequencyMHz != null && mainland.allows(wifiFrequencyMhzToChannel(it.frequencyMHz!!)!!) })
                assertFalse(plan.any { it.frequencyMHz in 5150..5350 || it.mode == P2pCreationMode.SYSTEM_DEFAULT })
            }
        }
    }

    @Test fun sharedRadioAlignsAllowedStationAndDefersOtherFiveGhzChannels() {
        assertEquals(5805, plan(5805).first().frequencyMHz)
        assertEquals(2437, plan(5200).first().frequencyMHz)
        assertEquals(listOf(5805, 2437, 2412, 2462, 5745, 5765, 5785, 5825), plan(5805).map { it.frequencyMHz })
    }

    @Test fun illegalManualChannelStopsBeforeAnyRequestAndLegalManualNeverFallsBack() {
        var calls = 0
        assertThrows(WirelessStartupException::class.java) {
            P2pStartupRecovery.create(null, {}, preferredChannel = 36, channelPolicy = mainland) { calls++ }
        }
        assertEquals(0, calls)
        assertThrows(P2pChannelUnavailableException::class.java) {
            P2pStartupRecovery.create(null, {}, preferredChannel = 149, channelPolicy = mainland) {
                calls++; throw P2pCreateRejected(WifiP2pManager.ERROR, "synthetic rejection")
            }
        }
        assertEquals(1, calls)
    }

    @Test fun automaticRejectionFallsBackWithinRegionAndCompatibilityCannotUnpin() {
        val frequencies = mutableListOf<Int?>()
        val result = P2pStartupRecovery.create(null, {}, channelPolicy = mainland) {
            frequencies += it.frequencyMHz
            if (it.frequencyMHz != 2437) throw P2pCreateRejected(WifiP2pManager.ERROR, "synthetic rejection")
        }
        assertEquals(listOf(5745, 5765, 5785, 5805, 5825, 2437), frequencies)
        assertEquals(2437, result.frequencyMHz)
        var calls = 0
        assertThrows(WirelessStartupException::class.java) {
            P2pStartupRecovery.create(null, {}, channelPolicy = mainland) {
                calls++; throw P2pConfigBuildCompatibilityFailure(NoSuchMethodError("synthetic framework"))
            }
        }
        assertEquals(1, calls)
    }

    @Test fun otherAndUnknownRegionsNeverAssume149AndDoNotChangeGenericCoreDefaults() {
        assertEquals(GalaxyP2pRegion.MAINLAND_CHINA, GalaxyP2pRegion.fromStored(null))
        assertEquals(GalaxyP2pRegion.OTHER_CONSERVATIVE, GalaxyP2pRegion.fromStored("JP"))
        val other = GalaxyP2pRegion.OTHER_CONSERVATIVE.policy
        assertTrue(P2pStartupRecovery.plan(5745, channelPolicy = other).all { it.frequencyMHz!! in 2400..2483 })
        assertEquals(5180, P2pStartupRecovery.plan(null).first().frequencyMHz)
    }
}
