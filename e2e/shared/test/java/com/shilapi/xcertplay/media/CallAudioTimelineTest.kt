package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class CallAudioTimelineTest {
    private var now = 100L
    private val logs = mutableListOf<String>()
    private val timeline = CallAudioTimeline(logs::add, { "audioMode=0" }, { now })

    @Test fun stopRequestDoesNotMarkCallReleasedBeforeBothResourcesEnd() {
        val down = timeline.start(CallAudioTimeline.Leg.DOWNLINK)
        val up = timeline.start(CallAudioTimeline.Leg.UPLINK)
        timeline.stopRequested(down); timeline.stopRequested(up)
        assertTrue(timeline.snapshot().contains("callStopping=2"))
        assertTrue(timeline.snapshot().contains("callPhase=ACTIVE"))
        timeline.released(up)
        assertTrue(timeline.snapshot().contains("callDownlinks=1 callUplinks=0"))
        assertTrue(timeline.snapshot().contains("afterCallReleaseMs=-1"))
        now = 200; timeline.released(down); now = 350
        assertTrue(timeline.snapshot().contains("callPhase=AFTER_RELEASE"))
        assertTrue(timeline.snapshot().contains("afterCallReleaseMs=150"))
        assertEquals(2, logs.count { it.contains("stage=RELEASED") })
    }

    @Test fun duplicateOldCallbacksCannotReleaseANewCallOrRestartItsClock() {
        val old = timeline.start(CallAudioTimeline.Leg.UPLINK)
        timeline.released(old)
        val next = timeline.start(CallAudioTimeline.Leg.UPLINK)
        timeline.released(old); timeline.stopRequested(old)
        assertTrue(timeline.snapshot().contains("callEpoch=2 callPhase=ACTIVE"))
        assertTrue(timeline.snapshot().contains("callUplinks=1"))
        assertTrue(timeline.snapshot().contains("afterCallReleaseMs=-1"))
        timeline.released(next)
    }

    @Test fun stopRequestIsIdempotentAndEachComponentHasItsOwnTicket() {
        val first = timeline.start(CallAudioTimeline.Leg.DOWNLINK)
        val replacement = timeline.start(CallAudioTimeline.Leg.DOWNLINK)
        timeline.stopRequested(first); timeline.stopRequested(first)
        timeline.released(first); timeline.released(first)
        assertTrue(timeline.snapshot().contains("callDownlinks=1"))
        assertEquals(1, logs.count { it.contains("stage=STOP_REQUESTED") })
        timeline.released(replacement)
        assertTrue(timeline.snapshot().contains("callPhase=AFTER_RELEASE"))
    }

    @Test fun sessionCloseDoesNotPretendToBeNormalCallRecoveryOrAcceptNewStreams() {
        val ticket = timeline.start(CallAudioTimeline.Leg.DOWNLINK)
        timeline.close(); timeline.close()
        assertNull(timeline.start(CallAudioTimeline.Leg.UPLINK))
        timeline.released(ticket)
        assertTrue(timeline.snapshot().contains("callPhase=CLOSED"))
        assertTrue(timeline.snapshot().contains("afterCallReleaseMs=-1"))
        assertEquals(1, logs.count { it.contains("stage=SESSION_CLOSED") })
    }

    @Test fun reporterAndSystemObservationFailuresDoNotAffectLifecycle() {
        val failing = CallAudioTimeline({ throw IllegalStateException() }, { throw SecurityException() }, { now })
        val ticket = failing.start(CallAudioTimeline.Leg.UPLINK)
        failing.stopRequested(ticket); failing.released(ticket)
        assertTrue(failing.snapshot().contains("callPhase=AFTER_RELEASE"))
        failing.close()
    }

    @Test fun separateSessionsHaveDifferentRunsAndNoCallStartsAsIdle() {
        assertTrue(timeline.snapshot().contains("callPhase=IDLE"))
        val other = CallAudioTimeline({}, nowMs = { now })
        assertNotEquals(timeline.snapshot().substringBefore(" callEpoch"), other.snapshot().substringBefore(" callEpoch"))
    }
}
