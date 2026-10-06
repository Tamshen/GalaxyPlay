package com.shilapi.xcertplay

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30], manifest = Config.NONE)
class VehicleSteeringCaptureTest {
    private var model = L7AudioTemplates.Model.L6
    private var connected = false
    private var now = 100L
    private val logs = mutableListOf<String>()
    private val store = L7SteeringTraceStore({ now }, logs::add, model = { model.id })
    private val capture = VehicleSteeringCapture({ model }, { connected }, store::begin, { now })

    @Test fun unknownWindowKeysAreObservedWithoutConsumptionOrPhoneCommands() {
        var sends = 0
        val callback = GalaxyMediaCallback(observeKey = capture::key) { _, _ -> sends++ }
        val event = KeyEvent(1, 2, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_UNKNOWN, 0, 0, 999, 321)
        assertFalse(callback.onKey(event, "window-key"))
        assertEquals(0, sends)
        assertTrue(logs.any { "scan=321" in it && "code=0" in it && "session=false" in it && "model=l6" in it })
        assertTrue(logs.any { "OBSERVE_ONLY" in it && "UNMAPPED_KEY" in it })
        assertFalse(logs.any { "999" in it })
    }

    @Test fun knownMediaStillForwardsExactlyOnceAndKeepsReleaseEvidence() {
        var sends = 0
        val callback = GalaxyMediaCallback(observeKey = capture::key) { _, _ -> sends++ }
        connected = true
        assertTrue(callback.onKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT), "media-session-key"))
        assertTrue(callback.onKey(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT), "media-session-key"))
        assertEquals(1, sends)
        assertEquals(2, store.snapshot().events.count { it.stage == "OBSERVE_ONLY" })
        assertTrue(logs.any { "action=1" in it && "session=true" in it })
    }

    @Test fun broadcastsRetainMissingInvalidAndUnknownNumericTypesWithoutExtras() {
        capture.broadcast(Intent(L7SteeringWheel.ACTION))
        capture.broadcast(Intent(L7SteeringWheel.ACTION).putExtra("type", "secret-type").putExtra("token", "secret-token"))
        capture.broadcast(Intent(L7SteeringWheel.ACTION).putExtra("type", 2))
        capture.broadcast(Intent("unrelated").putExtra("type", 3))
        assertEquals(3, store.snapshot().events.count { it.stage == "OBSERVE_ONLY" })
        assertTrue(logs.any { "type=missing" in it })
        assertTrue(logs.any { "type=invalid" in it })
        assertTrue(logs.any { "type=2" in it })
        assertFalse(logs.any { "secret" in it || "token" in it })
    }

    @Test fun vendorCustomActionOnlyReadsWhitelistedIntegersAndNeverInfersSuccess() {
        capture.customAction(Bundle().apply {
            putInt(Intent.ACTION_MEDIA_BUTTON, 87); putInt(Intent.ACTION_SEND, 0)
            putString("metadata", "private-title"); putString("device", "private-id")
        })
        capture.customAction(null)
        assertTrue(logs.any { "code=87 action=0" in it })
        assertTrue(logs.any { "code=missing action=missing" in it })
        assertTrue(logs.any { "UNMAPPED_CUSTOM_ACTION" in it })
        assertFalse(logs.any { "private" in it || "CHANNEL_WRITTEN" in it })
    }

    @Test fun typingAndCharacterMultipleEventsAreNotRecorded() {
        capture.key(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A), "window-key")
        capture.key(KeyEvent(1, "private-text", 1, 0), "window-key")
        assertTrue(logs.isEmpty())
    }

    @Test fun l7ExtraCaptureIsDisabledAndOtherModelsReactToConfirmedSelection() {
        model = L7AudioTemplates.Model.L7
        capture.broadcast(Intent(L7SteeringWheel.ACTION).putExtra("type", 1))
        capture.customAction(Bundle())
        capture.key(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT), "window-key")
        assertTrue(logs.isEmpty())
        model = L7AudioTemplates.Model.CUSTOM
        capture.broadcast(Intent(L7SteeringWheel.ACTION).putExtra("type", 1))
        assertTrue(logs.all { "model=custom" in it })
    }

    @Test fun modelTagStaysWithOriginalInputAcrossSelectionAndLateStages() {
        val trace = store.begin("mediacenter", 1)
        model = L7AudioTemplates.Model.L7
        trace.step("CHANNEL_WRITTEN")
        assertTrue(logs.all { "model=l6" in it })
        store.begin("manual-marker", -1).step("MARK", "USER_SUCCESS")
        assertTrue(logs.last().contains("model=l7"))
    }

    @Test fun highRateInputIsBoundedAndSuppressionIsReportedOnNextWindow() {
        repeat(100) { capture.broadcast(Intent(L7SteeringWheel.ACTION).putExtra("type", 2)) }
        assertEquals(40, store.snapshot().events.count { it.stage == "OBSERVE_ONLY" })
        now += 1000
        capture.broadcast(Intent(L7SteeringWheel.ACTION).putExtra("type", 2))
        assertTrue(logs.any { "RATE_LIMIT" in it && "suppressed=60" in it })
        assertEquals(41, store.snapshot().events.count { it.stage == "OBSERVE_ONLY" })
    }
}
