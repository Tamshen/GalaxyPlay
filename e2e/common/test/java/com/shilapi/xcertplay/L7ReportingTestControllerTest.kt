package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class L7ReportingTestControllerTest {
    private var now = 100L
    private var blocked = false
    private val logs = mutableListOf<String>()
    private class Session(val log: (String) -> Unit, val released: () -> Unit, val current: () -> Boolean) : L7ReportingSession {
        var starts = 0
        var closes = 0
        val actions = mutableListOf<L7ReportingAction>()
        override fun start() { starts++ }
        override fun action(value: L7ReportingAction) { if (current()) actions.add(value) }
        override fun close() { closes++ }
    }
    private val sessions = mutableListOf<Session>()
    private val engine = L7ReportingTestController({ _, log, released, current ->
        Session(log, released, current).also(sessions::add)
    }, { blocked }, { now }, logs::add)

    @Test fun entryIsReadOnlyAndPhoneSessionPreventsStart() {
        assertEquals(L7ReportingTestController.Phase.IDLE, engine.snapshot().phase)
        assertTrue(logs.isEmpty())
        blocked = true
        assertFalse(engine.start(L7ReportingKind.MEDIA))
        assertTrue(sessions.isEmpty())
    }
    @Test fun startIsExclusiveAndStopWaitsForCleanupBeforeAnotherTest() {
        assertTrue(engine.start(L7ReportingKind.MEDIA))
        assertFalse(engine.start(L7ReportingKind.NAVIGATION))
        engine.stop("USER"); engine.stop("DUPLICATE")
        assertEquals(1, sessions.single().closes)
        assertFalse(sessions.single().current())
        assertEquals(L7ReportingTestController.Phase.STOPPING, engine.snapshot().phase)
        assertFalse(engine.start(L7ReportingKind.MEDIA))
        sessions.single().released()
        assertTrue(engine.start(L7ReportingKind.NAVIGATION))
    }
    @Test fun endedAndWrongModuleActionsNeverReachTheSession() {
        engine.start(L7ReportingKind.MEDIA)
        engine.action(L7ReportingKind.NAVIGATION, L7ReportingAction.ROAD)
        engine.action(L7ReportingKind.MEDIA, L7ReportingAction.TRACK)
        engine.stop("USER")
        engine.action(L7ReportingKind.MEDIA, L7ReportingAction.PLAY_PAUSE)
        assertEquals(listOf(L7ReportingAction.TRACK), sessions.single().actions)
    }
    @Test fun deadlineAndRealConnectionStopOnceAndDoNotResume() {
        engine.start(L7ReportingKind.NAVIGATION)
        now += 119_999; engine.expire()
        assertEquals(0, sessions.single().closes)
        now++
        assertFalse(sessions.single().current()) // 即使主线程计时器尚未执行，端口也不能继续发布。
        engine.expire(); engine.expire()
        assertEquals(1, sessions.single().closes)
        assertTrue(logs.any { it.contains("reason=TIME_LIMIT") })
        sessions.single().released(); engine.start(L7ReportingKind.MEDIA)
        blocked = true; engine.action(L7ReportingKind.MEDIA, L7ReportingAction.TRACK)
        assertEquals(1, sessions.last().closes)
        assertTrue(sessions.last().actions.isEmpty())
    }
    @Test fun oldReleaseAndCallbacksCannotOverwriteNewRunButEvidenceStaysInLog() {
        engine.start(L7ReportingKind.MEDIA)
        val old = sessions.single()
        engine.stop("USER"); old.released()
        engine.start(L7ReportingKind.NAVIGATION)
        old.released(); old.log("stage=late")
        assertEquals(L7ReportingKind.NAVIGATION, engine.snapshot().kind)
        assertEquals(L7ReportingTestController.Phase.RUNNING, engine.snapshot().phase)
        assertTrue(logs.last().contains("run=1"))
        assertFalse(engine.snapshot().lines.any { it.contains("stage=late") })
        assertNull(engine.snapshot().firstIssue)
    }
    @Test fun evidenceIsBoundedAndReturnedCallDoesNotCreateDisplayPass() {
        engine.start(L7ReportingKind.MEDIA)
        sessions.single().log("stage=initialize exceptionType=ClassNotFoundException")
        repeat(40) { sessions.single().log("stage=update result=RETURNED display=NOT_VERIFIED") }
        assertEquals(12, engine.snapshot().lines.size)
        assertEquals(42, logs.size)
        assertTrue(engine.snapshot().firstIssue!!.contains("stage=initialize"))
        assertFalse(logs.any { it.contains("userObservation") })
        engine.observe(L7ReportingKind.MEDIA, false)
        engine.stop("USER"); sessions.single().released()
        engine.observe(L7ReportingKind.MEDIA, true)
        assertTrue(logs.any { it.contains("MISSING_OR_ABNORMAL") })
        assertTrue(logs.last().contains("origin=MANUAL"))
        assertTrue(logs.last().contains("stage=userCleanupObservation result=CLEARED"))
        engine.observe(L7ReportingKind.MEDIA, false)
        assertTrue(logs.last().contains("result=RESIDUAL_OR_ABNORMAL"))
    }
    @Test fun factoryFailureAndCancellationDuringCreationRemainRecoverable() {
        lateinit var cancelled: L7ReportingTestController
        val session = Session({}, {}, { false })
        var releases: (() -> Unit)? = null
        cancelled = L7ReportingTestController({ _, _, released, _ ->
            cancelled.stop("CREATING")
            assertFalse(cancelled.start(L7ReportingKind.MEDIA))
            releases = released
            session
        }, { false }, { 0 }, {})
        cancelled.start(L7ReportingKind.MEDIA)
        assertEquals(0, session.starts)
        assertEquals(1, session.closes)
        releases!!()
        assertEquals(L7ReportingTestController.Phase.STOPPED, cancelled.snapshot().phase)
        val failed = L7ReportingTestController({ _, _, _, _ -> throw SecurityException("secret") }, { false }, { 0 }, logs::add)
        failed.start(L7ReportingKind.NAVIGATION)
        assertEquals(L7ReportingTestController.Phase.STOPPED, failed.snapshot().phase)
        assertTrue(logs.any { it.contains("exceptionType=SecurityException") })
        assertFalse(logs.any { it.contains("secret") })
    }
    @Test fun unconfirmedCleanupDoesNotPermitOverlappingRegistration() {
        val failed = L7ReportingTestController({ _, _, _, _ -> object : L7ReportingSession {
            override fun start() {}
            override fun action(value: L7ReportingAction) {}
            override fun close() { throw SecurityException("secret") }
        } }, { false }, { 0 }, logs::add)
        failed.start(L7ReportingKind.MEDIA); failed.stop("USER")
        assertFalse(failed.start(L7ReportingKind.NAVIGATION))
        assertEquals(L7ReportingTestController.Phase.STOPPING, failed.snapshot().phase)
        assertTrue(logs.any { it.contains("completion=UNCONFIRMED") })
    }
    @Test fun publishedPreviewAndUserResultAreBoundToTheDisplayedRunAndStep() {
        engine.start(L7ReportingKind.MEDIA)
        sessions.single().log("stage=androidMedia result=RETURNED track=A playing=false elapsedMs=0 cover=1 display=NOT_VERIFIED")
        val first = engine.snapshot()
        assertEquals("A", first.preview!!.track)
        assertTrue(engine.observe(L7ReportingKind.MEDIA, true, first.run, first.step, first.phase))
        sessions.single().log("stage=androidMedia result=RETURNED track=B playing=true elapsedMs=15000 cover=2 display=NOT_VERIFIED")
        assertNull(engine.snapshot().observation)
        assertFalse(engine.observe(L7ReportingKind.MEDIA, true, first.run, first.step, first.phase))
        assertEquals("B", engine.snapshot().preview!!.track)
        assertEquals(15000L, engine.snapshot().preview!!.elapsedMs)
        assertTrue(logs.any { "stage=userObservation" in it && "step=${first.step}" in it })
    }
    @Test fun actionAndCleanupResetObservationContextAndRejectPendingOldClicks() {
        engine.start(L7ReportingKind.NAVIGATION)
        sessions.single().log("stage=fixture road=A active=true display=NOT_VERIFIED")
        val old = engine.snapshot()
        engine.action(L7ReportingKind.NAVIGATION, L7ReportingAction.REFRESH)
        assertFalse(engine.observe(L7ReportingKind.NAVIGATION, true, old.run, old.step, old.phase))
        val running = engine.snapshot()
        engine.stop("USER")
        assertFalse(engine.observe(L7ReportingKind.NAVIGATION, true))
        sessions.single().released()
        assertFalse(engine.observe(L7ReportingKind.NAVIGATION, true, running.run, running.step, running.phase))
        val ended = engine.snapshot()
        assertTrue(engine.observe(L7ReportingKind.NAVIGATION, false, ended.run, ended.step, ended.phase))
        assertEquals(false, engine.snapshot().cleanupObservation)
    }
    @Test fun portReturnsAndUntrustedValuesDoNotInventASampleOrDisplayPass() {
        engine.start(L7ReportingKind.MEDIA)
        sessions.single().log("stage=updateState result=RETURNED")
        sessions.single().log("stage=androidMedia result=RETURNED track=private playing=false elapsedMs=0 cover=1")
        assertNull(engine.snapshot().preview)
        assertNull(engine.snapshot().observation)
        assertNull(L7ReportingPreview.parse(L7ReportingKind.MEDIA,
            "stage=androidMedia result=RETURNED track=A playing=false elapsedMs=999999999999999999999999 cover=1"))
    }

}
