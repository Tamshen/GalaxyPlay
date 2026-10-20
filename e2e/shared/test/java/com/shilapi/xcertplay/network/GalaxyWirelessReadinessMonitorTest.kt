package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.network.WirelessLinkReadiness.Condition.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class GalaxyWirelessReadinessMonitorTest {
    @Test fun repeatedStartsObserveClientOnceAndRecoverOnlyOnConfirmedFailure() {
        val probes = AtomicInteger()
        val clients = AtomicInteger()
        val lost = CountDownLatch(1)
        val errors = AtomicInteger()
        GalaxyWirelessReadinessMonitor({ true }, {
            when (probes.incrementAndGet()) {
                1, 2 -> WirelessLinkReadiness(READY, 1)
                else -> WirelessLinkReadiness(GROUP_MISSING)
            }
        }, { clients.incrementAndGet() }, { if (it != GROUP_MISSING) errors.incrementAndGet(); lost.countDown() },
            {}, intervalMillis = 1).use { monitor ->
            repeat(5) { monitor.start() }
            assertTrue(lost.await(2, TimeUnit.SECONDS))
            assertEquals(4, probes.get())
            assertEquals(1, clients.get())
            assertEquals(0, errors.get())
            assertTrue(monitor.clientObserved)
        }
    }

    @Test fun probeReturningAfterTcpOrSupersessionCannotReportClientOrFailure() {
        val active = AtomicBoolean(true)
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val callbacks = AtomicInteger()
        val monitor = GalaxyWirelessReadinessMonitor(active::get, {
            entered.countDown(); resume.await(); returned.countDown()
            WirelessLinkReadiness(READY, 1)
        }, { callbacks.incrementAndGet() }, { callbacks.incrementAndGet() }, { callbacks.incrementAndGet() })
        try {
            monitor.start()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            active.set(false); resume.countDown()
            assertTrue(returned.await(2, TimeUnit.SECONDS))
            // 等待观察线程实际退出，避免用计时睡眠推断没有迟到回调。
            worker(monitor).join(2000)
            assertFalse(worker(monitor).isAlive)
            assertEquals(0, callbacks.get())
        } finally { resume.countDown(); monitor.close() }
    }

    @Test fun closeBeforeStartAndRepeatedCloseNeverLaunchProbe() {
        val monitor = GalaxyWirelessReadinessMonitor({ true }, { error("must not probe") }, {}, {}, {})
        monitor.close(); monitor.close(); monitor.start()
        assertEquals(Thread.State.NEW, worker(monitor).state)
    }

    @Test fun failedProbeIsUnknownAndDoesNotTurnTwoSeparateFailuresIntoRecovery() {
        val probes = AtomicInteger()
        val callbacks = AtomicInteger()
        val finished = CountDownLatch(1)
        GalaxyWirelessReadinessMonitor({ probes.get() < 4 }, {
            when (probes.incrementAndGet()) {
                2 -> throw java.io.IOException("unavailable")
                4 -> { finished.countDown(); WirelessLinkReadiness(READY) }
                else -> WirelessLinkReadiness(GROUP_MISSING)
            }
        }, {}, { callbacks.incrementAndGet() }, {}, intervalMillis = 1).use { monitor ->
            monitor.start()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            worker(monitor).join(2000)
            assertFalse(worker(monitor).isAlive)
            assertEquals(0, callbacks.get())
        }
    }

    private fun worker(monitor: GalaxyWirelessReadinessMonitor) =
        monitor.javaClass.getDeclaredField("worker").apply { isAccessible = true }.get(monitor) as Thread
}
