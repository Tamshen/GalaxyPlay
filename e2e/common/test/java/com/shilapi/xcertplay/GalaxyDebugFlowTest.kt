package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class GalaxyDebugFlowTest {
    private class Attempt : GalaxyDebugFlow.Attempt {
        var evidence = GalaxyDebugFlow.Evidence(question = true, canConfirm = true, token = "sample1")
        var released = true
        var stopped = 0
        var observations = mutableListOf<Boolean>()
        override fun poll() = evidence
        override fun observe(normal: Boolean): Boolean { observations.add(normal); return true }
        override fun stop() { stopped++ }
        override fun released() = released
    }
    private fun check(id: String, start: () -> GalaxyDebugFlow.Attempt) = GalaxyDebugFlow.Check(id, 0, 0, start)

    @Test fun automaticWorkContinuesAndHumanQuestionWaitsForARealAnswer() {
        val first = Attempt().apply { evidence = GalaxyDebugFlow.Evidence(automatic = true) }
        val second = Attempt()
        val flow = GalaxyDebugFlow(listOf(check("A") { first }, check("B") { second }), { 100L }, {})
        assertTrue(flow.start()); flow.poll(); assertEquals(GalaxyDebugFlow.Phase.RELEASING, flow.phase)
        flow.poll(); flow.poll(); assertEquals(GalaxyDebugFlow.Phase.QUESTION, flow.phase)
        repeat(5) { flow.poll() }; assertEquals(1, flow.completed)
        assertTrue(flow.answer(flow.generation, "sample1", GalaxyDebugFlow.Answer.MISSING))
        flow.poll(); assertEquals(GalaxyDebugFlow.Phase.FINISHED, flow.phase)
        assertEquals(listOf(false), second.observations); assertEquals(1, flow.missing)
    }

    @Test fun retryWaitsForReleaseAndOnlyRepeatsCurrentCheck() {
        val first = Attempt().apply { released = false }
        val retry = Attempt()
        var starts = 0
        var nextStarts = 0
        val flow = GalaxyDebugFlow(listOf(check("A") { if (starts++ == 0) first else retry },
            check("B") { nextStarts++; Attempt() }), { 100L }, {})
        flow.start(); flow.poll(); val oldGeneration = flow.generation
        assertTrue(flow.answer(oldGeneration, "sample1", GalaxyDebugFlow.Answer.RETRY))
        repeat(5) { flow.poll() }; assertEquals(1, starts); assertEquals(0, nextStarts)
        first.released = true; flow.poll(); flow.poll()
        assertEquals(2, starts); assertEquals(0, flow.completed)
        assertFalse(flow.answer(oldGeneration, "sample1", GalaxyDebugFlow.Answer.CONFIRM))
    }

    @Test fun changedSampleAndUnavailableConfirmationCannotBeRecordedAsSuccess() {
        val attempt = Attempt()
        val flow = GalaxyDebugFlow(listOf(check("A") { attempt }), { 100L }, {})
        flow.start(); flow.poll()
        attempt.evidence = GalaxyDebugFlow.Evidence(question = true, unavailable = true, token = "new")
        assertFalse(flow.answer(flow.generation, "sample1", GalaxyDebugFlow.Answer.CONFIRM))
        assertFalse(flow.answer(flow.generation, "new", GalaxyDebugFlow.Answer.CONFIRM))
        assertTrue(flow.answer(flow.generation, "new", GalaxyDebugFlow.Answer.MISSING))
        assertEquals(0, flow.completed); assertEquals(1, flow.unsupported)
        assertEquals(listOf(false), attempt.observations)
    }

    @Test fun cancellationStopsOwnedWorkAndNeverStartsNextStep() {
        val attempt = Attempt().apply { released = false }
        var next = 0
        val flow = GalaxyDebugFlow(listOf(check("A") { attempt }, check("B") { next++; Attempt() }), { 100L }, {})
        flow.start(); flow.poll(); flow.stop("BACKGROUND")
        assertFalse(flow.answer(flow.generation, "sample1", GalaxyDebugFlow.Answer.CONFIRM))
        attempt.released = true; flow.poll()
        assertEquals(GalaxyDebugFlow.Phase.STOPPED, flow.phase); assertEquals(0, next); assertEquals(1, attempt.stopped)
    }

    @Test fun unconfirmedReleaseBlocksRestartAndRecordsReason() {
        val attempt = Attempt().apply { released = false }
        var now = 100L
        val logs = mutableListOf<String>()
        val flow = GalaxyDebugFlow(listOf(check("A") { attempt }), { now }, logs::add)
        flow.start(); flow.poll(); flow.answer(flow.generation, "sample1", GalaxyDebugFlow.Answer.RETRY)
        now += 5_001; flow.poll()
        assertEquals(GalaxyDebugFlow.Phase.STOPPED, flow.phase); assertFalse(flow.start())
        assertTrue(logs.any { "RELEASE_UNCONFIRMED" in it })
    }

    @Test fun unsupportedAutomaticCheckIsLoggedWithoutHumanDialogOrFalsePass() {
        val attempt = Attempt().apply { evidence = GalaxyDebugFlow.Evidence(automatic = true, unavailable = true) }
        val flow = GalaxyDebugFlow(listOf(check("A") { attempt }), { 100L }, {})
        flow.start(); flow.poll(); flow.poll()
        assertEquals(GalaxyDebugFlow.Phase.FINISHED, flow.phase); assertEquals(1, flow.unsupported); assertEquals(0, flow.completed)
    }
}
