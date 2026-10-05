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
        var failInitialize = false
        var available = true
        var failRoad = false
        var failStart = false
        override fun ready() = available
        var failStop = false
        var duringStart: () -> Unit = {}
        override fun initialize() { calls += "init"; if (failInitialize) throw ClassNotFoundException() }
        override fun start() { calls += "start"; duringStart(); if (failStart) throw SecurityException() }
        override fun road(value: String) { calls += "road:$value"; if (failRoad) throw SecurityException() }
        override fun stop() { calls += "stop"; if (failStop) throw SecurityException() }
        override fun close() { calls += "close" }
    }
    private val tasks = java.util.ArrayDeque<Runnable>()
    private val worker = Executor { tasks.add(it) }
    private val port = Port()
    private var current = true
    private val logs = mutableListOf<String>()
    private val session = L7NavigationSession(port, { current }, worker, logs::add)
    private fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    private fun route(road: String? = "private-road") = CarPlayNavigationSnapshot(true, road)

    @Test fun bindingGapDoesNotCacheRouteAndRecoveryReplaysStartAndLatestRoad() {
        port.available = false; session.update(route()); drain()
        assertEquals(listOf("init"), port.calls)
        port.available = true; session.update(route("new-road")); drain()
        assertEquals(listOf("init", "start", "road:new-road"), port.calls)
        port.available = false; session.update(route("latest-road")); drain()
        port.available = true; session.update(route("latest-road")); drain()
        assertEquals(2, port.calls.count { it == "start" })
        assertEquals("road:latest-road", port.calls.last())
    }
    @Test fun cancellationDuringOutageSendsStopAfterServiceReturns() {
        session.update(route()); drain(); port.available = false
        session.update(CarPlayNavigationSnapshot()); drain()
        assertFalse(port.calls.contains("stop"))
        port.available = true; session.update(CarPlayNavigationSnapshot()); drain()
        assertEquals("stop", port.calls.last())
    }
    @Test fun roadFailureDoesNotCacheNameAndRepeatedFailureIsBounded() {
        port.failRoad = true; repeat(10) { session.update(route()); drain() }
        assertEquals(3, port.calls.count { it.startsWith("road:") })
        port.available = false; session.update(route()); drain()
        port.available = true; port.failRoad = false; session.update(route()); drain()
        assertEquals("road:private-road", port.calls.last())
    }

    @Test fun repeatedStartFailureIsBoundedEvenWhenCleanupSucceeds() {
        port.failStart = true; repeat(10) { session.update(route()); drain() }
        assertEquals(3, port.calls.count { it == "start" })
        assertEquals(3, port.calls.count { it == "stop" })
    }

    @Test fun noNavigationDoesNotInitializeSdkAndDuplicateGuidanceDoesNotRepeatStart() {
        session.update(CarPlayNavigationSnapshot()); drain(); assertTrue(port.calls.isEmpty())
        session.update(route()); drain(); session.update(route()); drain()
        assertEquals(listOf("init", "start", "road:private-road"), port.calls)
        assertFalse(logs.any { "private-road" in it })
    }
    @Test fun missingRoadClearsPreviousNameAndCancelStopsWithoutRepeatedStop() {
        session.update(route()); drain(); session.update(route(null)); drain()
        assertTrue(port.calls.contains("road:"))
        session.update(CarPlayNavigationSnapshot()); drain(); session.update(CarPlayNavigationSnapshot()); drain()
        assertEquals(1, port.calls.count { it == "stop" })
    }
    @Test fun sdkMissingAttemptsOnlyOnceForCurrentSession() {
        port.failInitialize = true; repeat(5) { session.update(route()); drain() }
        assertEquals(listOf("init"), port.calls)
        assertTrue(logs.any { "ClassNotFoundException" in it })
    }
    @Test fun lateGuidanceAfterCloseOrOwnerReplacementCannotPublish() {
        session.update(route()); current = false; drain(); assertTrue(port.calls.isEmpty())
        current = true; session.update(route()); session.close(); drain()
        assertEquals(listOf("close"), port.calls)
    }
    @Test fun closeDuringStartClearsRemoteStateWithoutPublishingRoad() {
        port.duringStart = { session.close() }; session.update(route()); drain()
        assertEquals(listOf("init", "start", "stop", "close"), port.calls)
    }
    @Test fun cleanupFailureStillUnregistersAndCloseIsIdempotent() {
        session.update(route()); drain(); port.failStop = true; session.close(); session.close(); drain()
        assertEquals(1, port.calls.count { it == "close" })
        assertTrue(logs.any { "stage=stop exceptionType=SecurityException" in it })
    }
}
