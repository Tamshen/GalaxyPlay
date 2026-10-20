package com.shilapi.xcertplay.orchestration

import android.net.wifi.WifiManager
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.AirPlayListenerIdentity
import com.shilapi.xcertplay.network.WirelessStartupDiagnostics
import com.shilapi.xcertplay.network.WifiScanPause
import com.shilapi.xcertplay.network.WifiScanPauseControl
import android.content.Context
import org.robolectric.RuntimeEnvironment
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class WirelessPerformanceLifecycleTest {
    @Test fun successfulWirelessSessionKeepsObservationUntilControllerTeardown() {
        withController { controller, _ ->
            ReflectionHelpers.setField(controller, "config", config(CarPlayTransport.WIRELESS, true))
            setPhase(controller, "WIRELESS")
            val closed = java.util.concurrent.atomic.AtomicInteger()
            var scanStarts = 0; var scanEnds = 0
            val scanControl = object : WifiScanPauseControl {
                override fun acquire(app: Context, owner: Any, current: () -> Boolean, log: (String) -> Unit) {}
                override fun release(app: Context, owner: Any, log: (String) -> Unit) {}
                override fun sessionActive(app: Context, owner: Any, current: () -> Boolean, log: (String) -> Unit) {
                    assertTrue(current()); scanStarts++
                }
                override fun sessionEnded(app: Context, owner: Any, log: (String) -> Unit) { scanEnds++ }
            }
            ReflectionHelpers.setField(controller, "wifiScanPause", WifiScanPause(RuntimeEnvironment.getApplication(), {}, scanControl))
            val diagnostics = WirelessStartupDiagnostics({ "" }, {}, onClose = { closed.incrementAndGet() })
            ReflectionHelpers.setField(controller, "wirelessDiagnostics", diagnostics)
            val watchdog = FirstTcpWatchdog(AirPlayListenerIdentity(0), { _, _ -> {} }, {})
            val listener = CarPlayController::class.java.getDeclaredMethod("wirelessSessionListener",
                Int::class.javaPrimitiveType, FirstTcpWatchdog::class.java).apply { isAccessible = true }
                .invoke(controller, 0, watchdog) as AirPlaySessionListener
            val session = mock(AirPlaySession::class.java)
            assertEquals(0, scanStarts)
            listener.onSessionActive(session)
            assertEquals(1, scanStarts)
            assertEquals(0, closed.get())
            assertTrue(diagnostics.summary().contains("observationPhase=STREAMING"))
            listener.onSessionEnded(mock(AirPlaySession::class.java))
            assertEquals(0, scanEnds)
            assertTrue(diagnostics.summary().contains("observationPhase=STREAMING"))
            listener.onSessionEnded(session)
            assertEquals(1, scanEnds)
            assertTrue(diagnostics.summary().contains("sessionActive=false"))
            assertTrue(diagnostics.summary().contains("waitingFor=session_recovery"))
            assertTrue(diagnostics.summary().contains("observationPhase=IDLE"))
            assertEquals(0, closed.get())
            controller.close()
            assertTrue(controller.awaitClosed(4000))
            assertEquals(1, closed.get())
            listener.onSessionActive(session)
            assertEquals(1, scanStarts)
        }
    }
    private fun held(controller: CarPlayController): WifiManager.WifiLock? {
        val owner: Any = ReflectionHelpers.getField(controller, "wirelessPerformanceLock")
        return ReflectionHelpers.getField(owner, "highPerformance")
    }

    @Test fun replacingSessionIgnoresOldEndAndControllerCloseRejectsLateActivation() {
        withController { controller, _ ->
            ReflectionHelpers.setField(controller, "config", config(CarPlayTransport.WIRELESS, true))
            val listener: AirPlaySessionListener = ReflectionHelpers.getField(controller, "sessionListener")
            val first = mock(AirPlaySession::class.java)
            val second = mock(AirPlaySession::class.java)
            listener.onSessionActive(first)
            val lock = held(controller)!!
            assertTrue(lock.isHeld)
            listener.onSessionActive(second)
            listener.onSessionEnded(first)
            assertTrue(lock.isHeld)
            listener.onSessionEnded(second)
            assertFalse(lock.isHeld)
            listener.onSessionActive(first)
            val finalLock = held(controller)!!
            controller.close()
            listener.onSessionActive(second)
            assertFalse(finalLock.isHeld)
            assertNull(held(controller))
        }
    }

    @Test fun wiredOrDisabledOptimizationNeverAcquiresWifiLocks() {
        for ((transport, enabled) in listOf(CarPlayTransport.WIRED to true, CarPlayTransport.WIRELESS to false)) {
            withController { controller, _ ->
                ReflectionHelpers.setField(controller, "config", config(transport, enabled))
                val listener: AirPlaySessionListener = ReflectionHelpers.getField(controller, "sessionListener")
                val session = mock(AirPlaySession::class.java)
                listener.onSessionActive(session)
                assertNull(held(controller))
                listener.onSessionEnded(session)
            }
        }
    }

    private fun config(transport: CarPlayTransport, enabled: Boolean) = CarPlayRuntimeConfig(
        mfiTarget = MfiTarget.LOCAL, transport = transport, wifiHighPerformanceEnabled = enabled,
        identification = Iap2IdentificationConfig("test", "test", "test", "test", "1", "1", 3))
}
