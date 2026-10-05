package com.shilapi.xcertplay.orchestration

import org.junit.Assert.*
import org.junit.Test

class IphoneUsbReenumerationTest {
    @Test fun unchangedDescriptorsDoNotRepeatTransition() {
        val gate = ready()
        gate.observe(setOf("old"))
        assertFalse(gate.accepts("old", false))
    }

    @Test fun newPathCanBeReauthorized() { assertTrue(ready().accepts("new", false)) }

    @Test fun reusedPathRequiresObservedRemoval() {
        val gate = ready()
        gate.observe(emptySet())
        gate.observe(setOf("old"))
        assertTrue(gate.accepts("old", false))
    }

    @Test fun matchingDetachAllowsReusedPath() {
        val gate = ready()
        assertFalse(gate.detached("other"))
        assertFalse(gate.accepts("old", false))
        assertTrue(gate.detached("old"))
        assertTrue(gate.accepts("old", false))
    }

    @Test fun completedDescriptorsAtSamePathCanProceed() { assertTrue(ready().accepts("old", true)) }

    @Test fun earlyAttachWaitsForTransitionCallback() {
        val gate = IphoneUsbReenumeration().apply { begin("old") }
        gate.detached("old")
        assertFalse(gate.accepts("new", true))
        gate.completeTransition()
        assertTrue(gate.accepts("new", true))
    }

    @Test fun resetDoesNotReusePriorRemovalOrCompletion() {
        val gate = ready()
        gate.detached("old")
        gate.clear()
        assertFalse(gate.isActive())
        assertFalse(gate.accepts("new", true))
        gate.begin("old")
        gate.completeTransition()
        assertFalse(gate.accepts("old", false))
    }

    @Test fun preexistingOtherDeviceCannotTakeOverEvenWhenConfigured() {
        val gate = IphoneUsbReenumeration().apply {
            begin("old", setOf("old", "other"))
            completeTransition()
        }
        gate.observe(setOf("other"))
        assertFalse(gate.accepts("other", false))
        assertFalse(gate.accepts("other", true))
        assertTrue(gate.accepts("new", false))
    }

    @Test fun pathOfOtherDeviceCanBeReusedOnlyAfterItsRemovalWasObserved() {
        val gate = IphoneUsbReenumeration().apply {
            begin("old", setOf("old", "other"))
            completeTransition()
        }
        gate.observe(emptySet())
        gate.observe(setOf("other"))
        assertTrue(gate.accepts("other", false))
    }

    private fun ready() = IphoneUsbReenumeration().apply { begin("old"); completeTransition() }
}
