package com.shilapi.xcertplay

import com.shilapi.xcertplay.hud.CarPlayNavigationSnapshot
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class L7NavigationSessionTest {
    private class Port : L7NavigationPort {
        val calls = mutableListOf<String>()
        val values = mutableListOf<CarPlayNavigationSnapshot>()
        var failStart = false
        var failGuidance = false
        var failStop = false
        var duringStart: () -> Unit = {}
        override fun initialize() { calls += "init" }
        override fun start() { calls += "start"; duringStart(); if (failStart) throw SecurityException() }
        override fun guidance(value: CarPlayNavigationSnapshot) {
            calls += "guidance"; if (failGuidance) throw SecurityException(); values += value
        }
        override fun stop() { calls += "stop"; if (failStop) throw SecurityException() }
        override fun close() { calls += "close" }
    }
    private val tasks = java.util.ArrayDeque<Runnable>()
    private val worker = Executor { tasks.add(it) }
    private val port = Port()
    private var current = true
    private var time = 0L
    private val logs = mutableListOf<String>()
    private val session = L7NavigationSession(port, { current }, worker, logs::add, { time })
    private val route = CarPlayNavigationSnapshot(true, "private-road", maneuverType = 1, segmentRemainingMeters = 300)
    private fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }

    @Test fun distanceAndTurnChangesPublishEvenWhenRoadDoesNotChange() {
        session.update(route); drain()
        val changed = route.copy(segmentRemainingMeters = 100, maneuverType = 2)
        session.update(changed); drain(); session.update(changed); drain()
        assertEquals(listOf(route, changed), port.values)
        assertEquals(1, port.calls.count { it == "start" })
        assertFalse(logs.any { "private-road" in it })
    }
    @Test fun heartbeatAndExplicitRefreshReplayCurrentSnapshot() {
        session.update(route); drain()
        time = 4_999; session.update(route); drain(); assertEquals(1, port.values.size)
        time = 5_000; session.update(route); drain(); assertEquals(2, port.values.size)
        session.refresh(); drain()
        assertEquals(3, port.values.size)
        assertEquals(2, port.calls.count { it == "start" })
    }
    @Test fun noRouteIsSilentAndCancellationEndsOnlyOnce() {
        session.update(CarPlayNavigationSnapshot()); drain(); assertTrue(port.calls.isEmpty())
        session.update(route); drain()
        session.update(CarPlayNavigationSnapshot()); drain(); session.update(CarPlayNavigationSnapshot()); drain()
        session.close(); drain()
        assertEquals(listOf("init", "start", "guidance", "stop", "close"), port.calls)
    }
    @Test fun queueCoalescesToLatestAndClosedOrReplacedOwnerCannotPublish() {
        session.update(route); session.update(route.copy(segmentRemainingMeters = 5)); drain()
        assertEquals(5L, port.values.single().segmentRemainingMeters)
        current = false; session.update(route); drain(); assertEquals(1, port.values.size)
        current = true; session.update(route); session.close(); drain()
        session.update(route); drain(); assertEquals(1, port.values.size)
    }
    @Test fun failuresAreBoundedAndChangedFieldsOrRefreshAllowRecovery() {
        port.failGuidance = true
        repeat(10) { session.update(route); drain() }
        assertEquals(3, port.calls.count { it == "guidance" })
        port.failGuidance = false; session.update(route.copy(segmentRemainingMeters = 10)); drain()
        assertEquals(10L, port.values.single().segmentRemainingMeters)
        session.refresh(); drain(); assertEquals(2, port.values.size)
    }
    @Test fun failedStartRetriesBeforeGuidanceAndStopsOnClose() {
        port.failStart = true
        repeat(10) { session.update(route); drain() }
        assertEquals(3, port.calls.count { it == "start" })
        assertTrue(port.values.isEmpty())
        port.failStart = false; session.refresh(); drain()
        assertEquals(listOf(route), port.values)
        session.close(); drain(); assertEquals(1, port.calls.count { it == "stop" })
    }
    @Test fun closeDuringStartSkipsGuidanceAndStillCleansUp() {
        port.duringStart = { session.close() }
        session.update(route); drain(); session.close(); drain()
        assertEquals(listOf("init", "start", "stop", "close"), port.calls)
    }
    @Test fun stopFailureStillReleasesAndDiagnosticFailureCannotStopPublishing() {
        session.update(route); drain(); port.failStop = true; session.close(); drain()
        assertEquals("close", port.calls.last())
        assertTrue(logs.any { "stage=stop exceptionType=SecurityException" in it })
        val other = Port()
        val safe = L7NavigationSession(other, { true }, worker, { throw IllegalStateException() })
        safe.update(route); drain(); assertEquals(listOf(route), other.values)
        safe.close(); drain()
    }
}
