package com.shilapi.xcertplay.orchestration

import android.net.wifi.WifiManager
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
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
