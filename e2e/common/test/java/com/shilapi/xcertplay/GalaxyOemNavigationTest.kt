package com.shilapi.xcertplay

import com.shilapi.xcertplay.hud.CarPlayNavigationSnapshot
import java.io.Closeable
import java.util.concurrent.Executor
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class GalaxyOemNavigationTest {
    private class Listener : GalaxyNavigationListener {
        val states = mutableListOf<Int>()
        val values = mutableListOf<CarPlayNavigationSnapshot>()
        val vendors = mutableListOf<Int>()
        var broken = false
        override fun status(value: Int) { states += value; if (broken) throw SecurityException("private") }
        override fun guidance(value: CarPlayNavigationSnapshot, vendor: Int) {
            if (broken) throw SecurityException("private")
            values += value; vendors += vendor
        }
    }
    private class Backend : GalaxyNavigationBackend {
        lateinit var ready: (Boolean) -> Unit
        lateinit var attach: (GalaxyNavigationListener) -> Unit
        var registrations = 0
        var closes = 0
        var denied = false
        var closeError = false
        override fun initialize(ready: (Boolean) -> Unit) { this.ready = ready }
        override fun register(listener: (GalaxyNavigationListener) -> Unit): Int {
            registrations++
            if (denied) throw GalaxyNavigationRegistrationException(10024)
            attach = listener
            return 42
        }
        override fun close() { closes++; if (closeError) throw SecurityException("private") }
    }
    private data class Timer(val action: () -> Unit, var cancelled: Boolean = false)
    private val tasks = java.util.ArrayDeque<Runnable>()
    private val worker = Executor { tasks += it }
    private val timers = mutableListOf<Timer>()
    private val backends = mutableListOf<Backend>()
    private val ports = mutableListOf<GalaxyOemNavigation>()
    private val logs = mutableListOf<String>()
    private val route = CarPlayNavigationSnapshot(true, "private-road", maneuverType = 1, segmentRemainingMeters = 300)
    private fun port() = GalaxyOemNavigation(RuntimeEnvironment.getApplication(), logs::add, worker,
        { _, action -> Timer(action).also(timers::add).let { timer -> Closeable { timer.cancelled = true } } },
        { Backend().also(backends::add) }).also(ports::add)
    private fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    private fun tick() { timers.first { !it.cancelled }.let { it.cancelled = true; it.action() }; drain() }
    private fun connect(port: GalaxyOemNavigation): Listener {
        port.initialize(); port.start(); port.guidance(route); drain()
        backends.last().ready(true); drain()
        return Listener().also { backends.last().attach(it); drain() }
    }
    @After fun close() { ports.forEach { it.close() }; drain() }

    @Test fun waitsForRegistrationAndListenerThenReplaysOnlyNewestRoute() {
        val port = port()
        port.initialize(); port.start(); port.guidance(route); drain()
        val service = backends.single()
        assertEquals(0, service.registrations)
        port.guidance(route.copy(segmentRemainingMeters = 10)); drain()
        service.ready(true); service.ready(true); drain()
        assertEquals(1, service.registrations)
        val listener = Listener()
        service.attach(listener); drain()
        assertEquals(listOf(16), listener.states)
        assertEquals(10L, listener.values.single().segmentRemainingMeters)
        assertEquals(listOf(42), listener.vendors)
        assertTrue(timers.all { it.cancelled })
        assertFalse(logs.any { "private-road" in it || "display=VERIFIED" in it })
    }
    @Test fun pendingRouteCancelledBeforeListenerDoesNotSendStaleStart() {
        val port = port()
        port.initialize(); port.guidance(route); drain()
        backends.single().ready(true); drain()
        port.stop(); drain()
        val listener = Listener(); backends.single().attach(listener); drain()
        assertTrue(listener.states.isEmpty()); assertTrue(listener.values.isEmpty())
    }
    @Test fun updatesAndRefreshNeverReregisterHealthyServiceAndEndOnce() {
        val port = port(); val listener = connect(port)
        repeat(20) { port.guidance(route.copy(segmentRemainingMeters = it.toLong())) }
        drain()
        assertEquals(2, listener.values.size)
        port.start(); port.guidance(route); drain()
        assertEquals(listOf(16, 16), listener.states)
        assertEquals(1, backends.single().registrations)
        port.stop(); drain(); port.stop(); drain(); port.close(); drain()
        assertEquals(listOf(16, 16, 17), listener.states)
        assertEquals(1, backends.single().closes)
    }
    @Test fun disconnectedServiceReRegistersAndRejectsOldCallbacks() {
        val port = port(); val listener = connect(port); val old = backends.single()
        old.ready(false); drain()
        port.guidance(route.copy(segmentRemainingMeters = 1)); drain()
        tick()
        old.ready(true); old.attach(Listener()); drain()
        assertEquals(1, old.registrations)
        assertEquals(listOf(16, 17), listener.states)
        val next = backends.last(); next.ready(true); drain()
        val nextListener = Listener(); next.attach(nextListener); drain()
        assertEquals(1L, nextListener.values.single().segmentRemainingMeters)
        assertEquals(1, old.closes)
    }
    @Test fun registrationDenialAndMissingCallbackHaveFiniteRetryBudget() {
        val port = port(); port.initialize(); port.guidance(route); drain()
        repeat(3) { index ->
            val backend = backends.last()
            backend.denied = true; backend.ready(true); drain()
            if (index < 2) tick()
        }
        assertEquals(3, backends.size)
        assertTrue(timers.all { it.cancelled })
        assertTrue(logs.any { "code=10024" in it })
        port.start(); drain()
        assertEquals(4, backends.size)
        tick() // 接口一直没有 ready 回调。
        tick() // 冷却后重建。
        assertEquals(5, backends.size)
    }
    @Test fun replacementDisposesOldSingletonBeforeNewInitAndOldCloseCannotStopNew() {
        val old = port(); val oldListener = connect(old)
        val next = port(); val listener = connect(next)
        old.close(); drain()
        assertEquals(listOf(16, 17), oldListener.states)
        assertEquals(listOf(16), listener.states)
        assertEquals(0, backends.last().closes)
        next.guidance(route.copy(maneuverType = 2)); drain()
        assertEquals(2, listener.values.last().maneuverType)
    }
    @Test fun closeCancelsRecoveryAndLateCallbacksEvenIfReleaseFails() {
        val port = port(); connect(port)
        val service = backends.single(); service.closeError = true
        var released = 0
        port.close { released++ }; drain()
        service.ready(true); service.attach(Listener()); drain()
        assertEquals(1, released)
        assertEquals(1, service.closes)
        assertTrue(timers.all { it.cancelled })
        assertFalse(logs.any { "private" in it })
    }
    @Test fun listenerFailureTriggersRecoveryWithoutLeakingRouteIntoDiagnostics() {
        val port = port(); val listener = connect(port)
        listener.broken = true
        port.guidance(route.copy(segmentRemainingMeters = 1)); drain()
        assertEquals(1, backends.single().closes)
        tick()
        assertEquals(2, backends.size)
        assertFalse(logs.any { "private" in it })
    }
    @Test fun refreshThenCloseStillStopsPreviouslyActiveRoute() {
        val port = port(); val listener = connect(port)
        port.start(); drain()
        port.close(); drain()
        assertEquals(listOf(16, 17), listener.states)
    }
    @Test fun failingListenerCannotPreventOtherConsumersFromReceivingEnd() {
        val port = port(); val first = connect(port)
        val second = Listener(); backends.single().attach(second); drain()
        first.broken = true
        port.close(); drain()
        assertEquals(17, second.states.last())
        assertEquals(1, backends.single().closes)
    }
}
