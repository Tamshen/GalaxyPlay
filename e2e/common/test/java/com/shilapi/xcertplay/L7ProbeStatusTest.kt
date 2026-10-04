package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class L7ProbeStatusTest {
    private fun item(result: L7ProbeOutcome, reason: String) = L7ProbeItem("PERM:example", "example", "PERMISSION", result, reason)

    @Test fun grantedPermissionDoesNotClaimVerifiedInterfaceAndUnknownDoesNotMeanUnsupported() {
        assertEquals(L7ProbeStatus.GRANTED, L7ProbeStatus.of(item(L7ProbeOutcome.OBSERVED, "GRANTED_NOT_CALLED")))
        assertEquals(L7ProbeStatus.PENDING, L7ProbeStatus.of(item(L7ProbeOutcome.UNKNOWN, "DEFINITION_NOT_VISIBLE")))
        assertEquals(L7ProbeStatus.NO_PERMISSION, L7ProbeStatus.of(item(L7ProbeOutcome.DENIED, "NOT_DECLARED")))
        assertEquals(L7ProbeStatus.ERROR, L7ProbeStatus.of(item(L7ProbeOutcome.UNKNOWN, "QUERY_FAILED")))
    }

    @Test fun successfulQueryStillShowsMissingAccessOrAbsentUsbFeature() {
        val query = L7ProbeItem("ENV-OVERLAY", "overlay", "ENVIRONMENT", L7ProbeOutcome.VERIFIED, "QUERY_ONLY", mapOf("allowed" to "false"))
        assertEquals(L7ProbeStatus.NO_PERMISSION, L7ProbeStatus.of(query))
        assertEquals(L7ProbeStatus.UNSUPPORTED, L7ProbeStatus.of(query.copy(id = "ENV-USB", facts = mapOf("usbHostFeature" to "false"))))
        assertEquals(L7ProbeStatus.SUPPORTED, L7ProbeStatus.of(query.copy(facts = mapOf("allowed" to "true"))))
    }

    @Test fun defaultFilterIncludesEveryStatusAndNewCollectionClearsOldFiltering() {
        assertTrue(L7ProbeStatus.entries.all { it.matches(0) })
        val state = L7ProbeUiState().apply { filter = 2; query = "hidden"; selectedReport = "old" }
        state.showCurrent()
        assertEquals(0, state.filter); assertEquals("", state.query); assertNull(state.selectedReport)
    }
}
