package com.shilapi.xcertplay.orchestration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WirelessConnectionProofTest {
    @Test fun renderedSessionSurvivesMissingTunnelButNotReplacementOrDisconnect() {
        val proof = WirelessConnectionProof<Any>()
        val first = Any()
        val replacement = Any()
        proof.begin(1) {}
        proof.activate(1, first)
        assertFalse(proof.hasRenderedFrame(1))
        proof.rendered(1, first)
        // 已显示画面可用于隧道超时保护，无需等待隧道认证完成。
        assertTrue(proof.hasRenderedFrame(1))
        assertFalse(proof.hasRenderedFrame(2))
        proof.activate(1, replacement)
        assertFalse(proof.hasRenderedFrame(1))
        proof.rendered(1, first)
        assertFalse(proof.hasRenderedFrame(1))
        proof.rendered(1, replacement)
        assertTrue(proof.hasRenderedFrame(1))
        proof.end(1, replacement)
        assertFalse(proof.hasRenderedFrame(1))
        proof.begin(2) {}
        proof.activate(2, Any())
        proof.rendered(1, replacement)
        assertFalse(proof.hasRenderedFrame(2))
    }

    @Test fun authenticationWithoutVideoDoesNotConfirm() {
        var saves = 0
        val proof = WirelessConnectionProof<Any>()
        proof.begin(1) { saves++ }
        proof.activate(1, Any())
        proof.authenticated(1)
        assertEquals(0, saves)
    }

    @Test fun bothEventsAreRequiredInEitherOrderAndOnlySaveOnce() {
        for (videoFirst in listOf(true, false)) {
            var saves = 0
            val session = Any()
            val proof = WirelessConnectionProof<Any>()
            proof.begin(1) { saves++ }
            proof.activate(1, session)
            if (videoFirst) proof.rendered(1, session) else proof.authenticated(1)
            assertEquals(0, saves)
            if (videoFirst) proof.authenticated(1) else proof.rendered(1, session)
            proof.rendered(1, session)
            proof.authenticated(1)
            assertEquals(1, saves)
        }
    }

    @Test fun oldGenerationAndOldSessionCannotConfirmANewConnection() {
        var saves = 0
        val old = Any()
        val current = Any()
        val proof = WirelessConnectionProof<Any>()
        proof.begin(1) { saves++ }
        proof.activate(1, old)
        proof.authenticated(1)
        proof.begin(2) { saves++ }
        proof.activate(2, current)
        proof.rendered(1, old)
        proof.authenticated(1)
        proof.rendered(2, old)
        proof.authenticated(2)
        assertEquals(0, saves)
        proof.rendered(2, current)
        assertEquals(1, saves)
    }

    @Test fun endedOrClearedSessionCannotBeLearnedByLateCallbacks() {
        val session = Any()
        var saves = 0
        val proof = WirelessConnectionProof<Any>()
        proof.begin(1) { saves++ }
        proof.activate(1, session)
        proof.rendered(1, session)
        proof.end(1, session)
        proof.authenticated(1)
        proof.clear()
        proof.activate(1, session)
        proof.authenticated(1)
        proof.rendered(1, session)
        assertEquals(0, saves)
    }

    @Test fun replacementSessionNeedsItsOwnAuthenticationAndFrame() {
        val first = Any()
        val second = Any()
        var saves = 0
        val proof = WirelessConnectionProof<Any>()
        proof.begin(1) { saves++ }
        proof.activate(1, first)
        proof.authenticated(1)
        proof.activate(1, second)
        proof.rendered(1, second)
        assertEquals(0, saves)
        proof.authenticated(1)
        assertEquals(1, saves)
    }
}
