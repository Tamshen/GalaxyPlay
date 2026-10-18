package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.Assert.*
import org.junit.Test

class L7WifiDirectPolicyTest {
    @Test fun onlyConfirmedL7WirelessDirectUsesTheFixedPath() {
        for (model in listOf("l7", "l6", "custom", "unknown")) {
            for (mode in WirelessHotspotMode.entries) {
                assertEquals(model == "l7" && mode == WirelessHotspotMode.WIFI_P2P,
                    L7WifiDirectPolicy.enabled(model, true, mode))
                assertFalse(L7WifiDirectPolicy.enabled(model, false, mode))
            }
            assertEquals(if (model == "l7") 149 else null,
                L7WifiDirectPolicy.fixedChannel(model, GalaxyP2pRegion.MAINLAND_CHINA))
            assertNull(L7WifiDirectPolicy.fixedChannel(model, GalaxyP2pRegion.OTHER_CONSERVATIVE))
        }
    }
}
