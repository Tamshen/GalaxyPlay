package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.network.WirelessLinkReadiness.Condition.*
import org.junit.Assert.*
import org.junit.Test

class GalaxyWirelessReadinessTest {
    @Test fun onlyRepeatedSameDefiniteFailureTriggersEarlyRecovery() {
        for (condition in listOf(GROUP_MISSING, GROUP_CHANGED, INTERFACE_MISSING, ADDRESS_MISSING)) {
            val policy = GalaxyWirelessReadiness()
            assertFalse(policy.observe(WirelessLinkReadiness(condition)))
            assertTrue(policy.observe(WirelessLinkReadiness(condition)))
        }
    }

    @Test fun unknownReadyAndDifferentFailureBreakTheConsecutiveEvidence() {
        for (between in listOf(UNKNOWN, READY, ADDRESS_MISSING)) {
            val policy = GalaxyWirelessReadiness()
            assertFalse(policy.observe(WirelessLinkReadiness(GROUP_MISSING)))
            assertFalse(policy.observe(WirelessLinkReadiness(between)))
            assertFalse(policy.observe(WirelessLinkReadiness(GROUP_MISSING)))
        }
    }

    @Test fun NoClientAndUnknownNeverProveFailureAndObservedClientIsRemembered() {
        val policy = GalaxyWirelessReadiness()
        repeat(20) { assertFalse(policy.observe(WirelessLinkReadiness(READY, 0))) }
        repeat(20) { assertFalse(policy.observe(WirelessLinkReadiness(UNKNOWN))) }
        assertFalse(policy.clientObserved)
        assertFalse(policy.observe(WirelessLinkReadiness(READY, 1)))
        assertTrue(policy.clientObserved)
        assertFalse(policy.observe(WirelessLinkReadiness(UNKNOWN)))
        assertTrue(policy.clientObserved)
    }
}
