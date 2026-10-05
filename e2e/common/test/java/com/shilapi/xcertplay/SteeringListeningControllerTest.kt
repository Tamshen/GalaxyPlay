package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class SteeringListeningControllerTest {
    private var now = 100L
    private val logs = mutableListOf<String>()
    private val store = L7SteeringTraceStore({ now }, logs::add, model = { "l6" })
    private val controller = SteeringListeningController({ now }, logs::add)
    private fun input(source: String = "vehicle-window-key", detail: String = "code=87 action=0 repeat=0"): L7SteeringTrace =
        store.begin(source, 3, detail).also { controller.input(it, detail) }
    private fun settle() { now += 350; controller.poll("l6") }

    @Test fun disabledListenerAndEmptyWaitNeverAskForAnInventedKey() {
        input(); settle()
        assertNull(controller.snapshot().pending)
        controller.start("l6"); settle()
        assertEquals(SteeringListeningController.Phase.LISTENING, controller.snapshot().phase)
        assertNull(controller.snapshot().pending)
        assertFalse(controller.answer(1, "RIGHT"))
    }
    @Test fun receivedDownReleaseAndOtherEntranceBindToTheOriginalTraces() {
        controller.start("l6")
        val down = input()
        now += 30; val up = input(detail = "code=87 action=1 repeat=0")
        val oem = input("mediacenter", "registered=true")
        settle()
        val pending = controller.snapshot().pending!!
        assertEquals(listOf(down.id, up.id, oem.id), pending.traces.map { it.id })
        assertTrue(controller.answer(pending.id, "RIGHT"))
        val labels = store.snapshot().events.filter { it.stage == "USER_LABEL" }
        assertEquals(listOf(down.id, up.id, oem.id), labels.map { it.id })
        assertTrue(labels.all { "key=RIGHT" in it.detail && "sample=${pending.id}" in it.detail })
    }
    @Test fun inputDuringLabelingIsNotMisassignedAndStaleDialogsCannotLabelNextSample() {
        controller.start("l6"); input(); settle()
        val first = controller.snapshot().pending!!
        val whilePrompting = input()
        assertTrue(controller.answer(first.id, "LEFT"))
        input(); settle()
        val next = controller.snapshot().pending!!
        assertFalse(controller.answer(first.id, "RIGHT"))
        assertFalse(next.traces.any { it.id == whilePrompting.id })
        assertTrue(controller.answer(next.id, "SKIP"))
        assertTrue(store.snapshot().events.any { it.stage == "USER_SKIP" })
    }
    @Test fun navigationMetadataAndReleaseOnlyDoNotTriggerLabeling() {
        controller.start("l6")
        input("session"); input("phonePlayback"); input(detail = "code=4 action=0 repeat=0")
        input(detail = "code=87 action=1 repeat=0")
        settle(); assertNull(controller.snapshot().pending)
    }
    @Test fun repeatedHeldInputIsBoundedAndPromptsAfterMaximumGroupingTime() {
        controller.start("l6"); input()
        repeat(40) { now += 40; input(detail = "code=87 action=0 repeat=1") }
        controller.poll("l6")
        assertEquals(24, controller.snapshot().pending!!.traces.size)
    }
    @Test fun backgroundOrTimeoutRejectsLateLabelAndNeverResumesAutomatically() {
        controller.start("l6"); input(); settle()
        val id = controller.snapshot().pending!!.id
        controller.stop("BACKGROUND")
        assertFalse(controller.answer(id, "LEFT"))
        controller.start("l6"); input(); settle()
        now += 120_000; controller.poll("l6")
        assertEquals(SteeringListeningController.Phase.STOPPED, controller.snapshot().phase)
        assertFalse(controller.active())
    }
    @Test fun changingVehicleEndsListenerInsteadOfMixingEvidence() {
        controller.start("l6"); input(); settle()
        controller.poll("l7")
        assertFalse(controller.active())
        assertNull(controller.snapshot().pending)
        assertTrue(logs.any { "MODEL_CHANGED" in it })
    }
    @Test fun restartingRetainsLogsButDoesNotReattachOldSamples() {
        controller.start("l6"); input(); settle()
        val old = controller.snapshot().pending!!.id
        controller.stop("USER"); controller.start("l6")
        assertFalse(controller.answer(old, "LEFT"))
        assertTrue(controller.snapshot().samples.isEmpty())
        assertTrue(logs.any { "STEERING_TRACE" in it })
    }
}
