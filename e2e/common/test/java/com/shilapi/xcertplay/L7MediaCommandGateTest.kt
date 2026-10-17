package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class L7MediaCommandGateTest {
    @Test fun nativeDownIsImmediateAndHeldStandardCopiesReleaseAndCancelDoNotResend() {
        var time = 100L
        val gate = L7MediaCommandGate { time }
        val next = gate.nativeEvent(1, 200087)!!
        assertTrue(gate.accept(next, "native-key:200087"))
        assertNull(gate.nativeEvent(1, 200087))
        time += 1500
        assertFalse(gate.accept(next, "window-key"))
        assertFalse(gate.accept(next, "mediacenter"))
        assertNull(gate.nativeEvent(2, 200087))
        assertNotNull(gate.nativeEvent(1, 200087))
        assertTrue(gate.accept(next, "native-key:200087"))
        assertNull(gate.nativeEvent(3, 200087))
        gate.clearNative()
        time += 81
        assertTrue(gate.accept(next, "window-key"))
    }

    @Test fun standardFirstStillDeduplicatesNativeAndUnknownKeysNeverSuppressStandard() {
        val gate = L7MediaCommandGate { 100L }
        val next = com.shilapi.xcertplay.airplay.CarPlayMediaButton.NEXT
        assertTrue(gate.accept(next, "window-key"))
        assertEquals(next, gate.nativeEvent(1, 200087))
        assertFalse(gate.accept(next, "native-key:200087"))
        assertNull(gate.nativeEvent(1, 200231))
        assertNull(gate.nativeEvent(1, 200089))
        assertTrue(gate.accept(com.shilapi.xcertplay.airplay.CarPlayMediaButton.PREVIOUS, "window-key"))
    }

    @Test fun toggleCopiesCannotBecomeOppositeAfterPhoneStateChangesButExplicitOppositeSurvives() {
        val gate = L7MediaCommandGate { 100L }
        val button = com.shilapi.xcertplay.airplay.CarPlayMediaButton
        gate.nativeEvent(1, 200085)
        assertTrue(gate.accept(button.PAUSE, "native-key:200085"))
        assertFalse(gate.accept(button.PLAY, "window-key:KEYCODE_MEDIA_PLAY_PAUSE"))
        assertTrue(gate.accept(button.PLAY, "controller-play"))
        assertTrue(gate.accept(button.PAUSE, "controller-pause"))
    }
    @Test fun missingNativeReleaseCannotPermanentlySuppressStandardKeys() {
        var time = 100L
        val gate = L7MediaCommandGate { time }
        val next = gate.nativeEvent(1, 200087)!!
        assertTrue(gate.accept(next, "native-key:200087"))
        time += 6000
        assertTrue(gate.accept(next, "window-key"))
        assertNull(gate.nativeEvent(1, 200087))
    }
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
