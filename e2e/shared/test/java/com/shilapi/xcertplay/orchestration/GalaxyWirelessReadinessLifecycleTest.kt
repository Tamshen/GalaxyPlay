package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.AirPlayListenerIdentity
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.AirPlayTcpAccepted
import com.shilapi.xcertplay.network.GalaxyWirelessReadinessMonitor
import com.shilapi.xcertplay.network.WirelessLinkReadiness
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

/** 经控制器的真实回调验证观察线程释放，内部探测与旧代次不能关闭当前观察。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class GalaxyWirelessReadinessLifecycleTest {
    @Test fun actualExternalTcpStopsReadinessButInternalAndOldListenerDoNot() = withController { controller, _ ->
        setPhase(controller, "WIRELESS")
        withMonitor(controller) { monitor, worker ->
            val owner = AirPlayListenerIdentity(0)
            val watchdog = FirstTcpWatchdog(owner, { _, _ -> {} }, {})
            val method = CarPlayController::class.java.getDeclaredMethod("wirelessSessionListener",
                Int::class.javaPrimitiveType, FirstTcpWatchdog::class.java).apply { isAccessible = true }
            val current = method.invoke(controller, 0, watchdog) as AirPlaySessionListener
            val stale = method.invoke(controller, -1, watchdog) as AirPlaySessionListener
            current.onTcpAccepted(AirPlayTcpAccepted(owner, true, System.nanoTime()))
            stale.onTcpAccepted(AirPlayTcpAccepted(owner, false, System.nanoTime()))
            current.onTcpAccepted(AirPlayTcpAccepted(AirPlayListenerIdentity(0), false, System.nanoTime()))
            assertFalse(ReflectionHelpers.getField<AtomicBoolean>(monitor, "closed").get())
            current.onTcpAccepted(AirPlayTcpAccepted(owner, false, System.nanoTime()))
            worker.join(2000)
            assertFalse(worker.isAlive)
            assertTrue(ReflectionHelpers.getField<AtomicBoolean>(monitor, "closed").get())
        }
    }

    @Test fun controllerTeardownStopsAndReleasesReadiness() = withController { controller, _ ->
        setPhase(controller, "WIRELESS")
        withMonitor(controller) { _, worker ->
            controller.close()
            assertTrue(controller.awaitClosed(4000))
            worker.join(2000)
            assertFalse(worker.isAlive)
            assertNull(ReflectionHelpers.getField<Any?>(controller, "wirelessReadiness"))
        }
    }

    private fun withMonitor(controller: CarPlayController, block: (GalaxyWirelessReadinessMonitor, Thread) -> Unit) {
        ReflectionHelpers.setField(controller, "config", CarPlayRuntimeConfig(
            mfiTarget = MfiTarget.LOCAL, transport = CarPlayTransport.WIRELESS,
            identification = Iap2IdentificationConfig("test", "test", "test", "test", "1", "1", 3)))
        val entered = CountDownLatch(1)
        val monitor = GalaxyWirelessReadinessMonitor({ true }, {
            entered.countDown()
            CountDownLatch(1).await()
            WirelessLinkReadiness(WirelessLinkReadiness.Condition.READY)
        }, {}, {}, {})
        ReflectionHelpers.setField(controller, "wirelessReadiness", monitor)
        try {
            monitor.start()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            block(monitor, ReflectionHelpers.getField(monitor, "worker"))
        } finally { monitor.close() }
    }
}
