package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class L7MediaCommandGateTest {
    @Test fun crossSourceDuplicateDropsButSameSourceDoublePressAndOppositeCommandSurvive() {
        var time = 100L
        val gate = L7MediaCommandGate { time }
        assertTrue(gate.accept(3, "window-key"))
        time += 10
        assertFalse(gate.accept(3, "mediacenter"))
        assertTrue(gate.accept(3, "window-key"))
        assertTrue(gate.accept(4, "mediacenter"))
    }
    @Test fun outsideWindowOrNewSessionDoesNotSwallowAnotherOperation() {
        var time = 100L
        val gate = L7MediaCommandGate { time }
        assertTrue(gate.accept(3, "mediacenter"))
        time += 81
        assertTrue(gate.accept(3, "window-key"))
        assertTrue(L7MediaCommandGate { time }.accept(3, "media-session-key"))
    }
}
