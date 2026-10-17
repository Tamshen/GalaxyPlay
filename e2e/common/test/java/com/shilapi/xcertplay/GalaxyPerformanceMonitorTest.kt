package com.shilapi.xcertplay

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class GalaxyPerformanceMonitorTest {
    private class Schedule : GalaxyPerformanceSchedule {
        var task: Runnable? = null
        var closed = false
        override fun start(task: Runnable) { this.task = task }
        override fun close() { closed = true }
        override fun awaitClosed(timeoutMs: Long) = closed
    }
    private fun input(time: Long) = GalaxyPerformanceInput(time, time / 2, 1, 2, null, null, null, null, null, null, emptyList(), emptyList())
    @Test fun disabledDoesNotCreateTasksAndStartStopAreIdempotentWithBoundedHistory() {
        var time = 0L; var schedules = 0; var reads = 0
        val worker = Schedule(); val events = mutableListOf<String>()
        var history: List<GalaxyPerformanceSample> = emptyList()
        val monitor = GalaxyPerformanceMonitor({ reads++; input(time) }, { _, values -> history = values },
            { null }, events::add, { schedules++; worker }, { time }, { time * 1_000_000 }, { 0 })
        assertEquals(0, schedules); assertEquals(0, reads); assertFalse(monitor.running)
        assertTrue(monitor.start()); assertFalse(monitor.start()); assertEquals(1, schedules)
        repeat(100) { time += 1000; worker.task!!.run() }
        assertEquals(60, history.size); assertEquals(100, reads)
        assertEquals(20, events.count { " SAMPLE " in it })
        monitor.close(); monitor.close(); worker.task!!.run()
        assertEquals(100, reads); assertTrue(worker.closed); assertTrue(monitor.awaitClosed(0))
        assertEquals(1, events.count { " STOP " in it })
    }
    @Test fun deepSamplingIsExplicitAndExpiresWithoutRestartingBasicWorker() {
        var time = 0L; val modes = mutableListOf<Boolean>(); val worker = Schedule()
        val monitor = GalaxyPerformanceMonitor({ deep -> modes.add(deep); input(time) }, { _, _ -> },
            { null }, {}, { worker }, { time }, { 0 }, { 0 })
        try {
            assertFalse(monitor.deep()); monitor.start(); worker.task!!.run()
            monitor.deep(); worker.task!!.run(); time = 15000; worker.task!!.run()
            assertEquals(listOf(false, true, false), modes)
        } finally { monitor.close() }
    }
    @Test fun aBlockedReadCannotPublishAfterStopOrBlockStop() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val stopped = CountDownLatch(1)
        val worker = Schedule(); var published = 0
        val monitor = GalaxyPerformanceMonitor({ entered.countDown(); release.await(); input(1000) }, { _, _ -> published++ },
            { null }, {}, { worker }, { 0 }, { 0 }, { 0 })
        monitor.start()
        val sampling = Thread { worker.task!!.run() }.apply { start() }
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            Thread { monitor.close(); stopped.countDown() }.start()
            assertTrue(stopped.await(1, TimeUnit.SECONDS))
            release.countDown(); sampling.join(1000)
            assertFalse(sampling.isAlive); assertEquals(0, published)
        } finally { release.countDown(); monitor.close() }
    }
    @Test fun readFailurePublishesUnknownAndDoesNotStopLaterSampling() {
        var fail = true; var latest: GalaxyPerformanceSample? = null; val worker = Schedule()
        val monitor = GalaxyPerformanceMonitor({ if (fail) error("private value") else input(1000) }, { sample, _ -> latest = sample },
            { null }, {}, { worker }, { 0 }, { 0 }, { 0 })
        try {
            monitor.start(); worker.task!!.run(); assertTrue(latest!!.failed); assertNull(latest!!.cpuCorePercent)
            fail = false; worker.task!!.run(); assertFalse(latest!!.failed)
        } finally { monitor.close() }
    }
}
