package com.shilapi.xcertplay.network

import org.junit.Assert.*
import org.junit.Test

class L7WifiDirectPolicyTest {
    @Test fun onlyL7UsesFixedChannelWithinItsSelectedRegion() {
        for (model in listOf("l7", "l6", "custom", "unknown")) {
            assertEquals(if (model == "l7") 149 else null,
                L7WifiDirectPolicy.fixedChannel(model, GalaxyP2pRegion.MAINLAND_CHINA))
            assertNull(L7WifiDirectPolicy.fixedChannel(model, GalaxyP2pRegion.OTHER_CONSERVATIVE))
        }
    }
}
