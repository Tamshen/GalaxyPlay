package com.shilapi.xcertplay.network

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** 只在发出启动请求至首个 TCP 之间观察；有界框架查询不占用超时计时线程。 */
internal class GalaxyWirelessReadinessMonitor(
    private val current: () -> Boolean,
    private val probe: () -> WirelessLinkReadiness,
    private val onClientObserved: () -> Unit,
    private val onLost: (WirelessLinkReadiness.Condition) -> Unit,
    private val log: (String) -> Unit,
    private val intervalMillis: Long = 2_000,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val policy = GalaxyWirelessReadiness()
    val clientObserved: Boolean get() = policy.clientObserved
    private val worker = Thread(::observe, "galaxy-wireless-readiness").apply { isDaemon = true }

    @Synchronized fun start() {
        if (!closed.get() && worker.state == Thread.State.NEW) worker.start()
    }

    private fun observe() {
        try {
            var reportedClient = false
            while (!closed.get() && current()) {
                val value = try { probe() } catch (_: Exception) {
                    WirelessLinkReadiness(WirelessLinkReadiness.Condition.UNKNOWN)
                }
                if (closed.get() || !current()) return
                val lost = policy.observe(value)
                runCatching { log("WIRELESS_READINESS condition=${value.condition} reportedClients=${value.reportedClients ?: "unknown"} " +
                    "clientObserved=${policy.clientObserved} dhcp=UNVERIFIED airplayTcp=false " +
                    "atNs=${System.nanoTime()} failureConfirmed=$lost") }
                if (lost) { onLost(value.condition); return }
                if (policy.clientObserved && !reportedClient) { reportedClient = true; onClientObserved() }
                Thread.sleep(intervalMillis)
            }
        } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }

    override fun close() {
        closed.set(true)
        if (Thread.currentThread() !== worker) worker.interrupt()
    }
}
