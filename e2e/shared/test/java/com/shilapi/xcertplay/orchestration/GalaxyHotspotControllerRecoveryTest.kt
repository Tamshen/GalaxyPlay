package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.AirPlayListenerIdentity
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.network.GalaxyHotspotAddressRecovery
import com.shilapi.xcertplay.transport.Iap2WirelessCarPlayEndpoint
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.net.InetAddress
import java.net.Inet6Address
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class GalaxyHotspotControllerRecoveryTest {
    private val ipv4 = InetAddress.getByName("192.168.43.1")
    private val raw6 = InetAddress.getByName("fe80::1234") as Inet6Address
    private val ipv6 = Inet6Address.getByAddress(null, raw6.address, 22)
    private val endpoint = Iap2WirelessCarPlayEndpoint("test-ap", "test12345", 0,
        Iap2WirelessSecurity.WPA_WPA2, listOf("192.168.43.1"), 7000, "test-id", "abcd", "1.0")

    private fun withWirelessListener(check: (CarPlayController, AirPlaySessionListener,
                                           AirPlaySession, MutableList<String>, GalaxyHotspotAddressRecovery) -> Unit) {
        withController { controller, _ ->
            setPhase(controller, "WIRELESS")
            val remembered = mutableListOf<String>()
            val addresses = listOf(ipv4, ipv6)
            val generation: AtomicInteger = ReflectionHelpers.getField(controller, "wirelessGeneration")
            val policy = GalaxyHotspotAddressRecovery(addresses, null, { addresses },
                { !ReflectionHelpers.getField<Boolean>(controller, "closed") && generation.get() == 0 }, remembered::add)
            ReflectionHelpers.setField(controller, "hotspotAddressRecovery", policy)
            ReflectionHelpers.setField(controller, "wirelessAirPlayEndpoint", endpoint)
            val watchdog = FirstTcpWatchdog(AirPlayListenerIdentity(0), { _, _ -> {} }, {})
            ReflectionHelpers.setField(controller, "firstTcpWatchdog", watchdog)
            val listener = CarPlayController::class.java.getDeclaredMethod("wirelessSessionListener",
                Int::class.javaPrimitiveType, FirstTcpWatchdog::class.java).apply { isAccessible = true }
                .invoke(controller, 0, watchdog) as AirPlaySessionListener
            val session = mock(AirPlaySession::class.java)
            `when`(session.localAddress).thenReturn(raw6)
            ReflectionHelpers.setField(controller, "activeSession", session)
            check(controller, listener, session, remembered, policy)
        }
    }

    @Test fun establishedTunnelKeepsActualIpv6AndOnlyTheFirstRenderedFrameTeachesIt() {
        withWirelessListener { controller, listener, session, remembered, _ ->
            listener.onSessionActive(session)
            val actual: Iap2WirelessCarPlayEndpoint = ReflectionHelpers.getField(controller, "wirelessAirPlayEndpoint")
            assertEquals(listOf("fe80:0:0:0:0:0:0:1234"), actual.ipAddresses)
            assertEquals(endpoint.deviceIdentifier, actual.deviceIdentifier)
            assertTrue(remembered.isEmpty())
            listener.onVideoFrameRendered(session)
            listener.onVideoFrameRendered(session)
            assertEquals(listOf("IPv6"), remembered)
        }
    }

    @Test fun oldGenerationOrOtherSessionFrameCannotTeachTheReplacement() {
        withWirelessListener { controller, listener, session, remembered, _ ->
            listener.onSessionActive(session)
            listener.onVideoFrameRendered(mock(AirPlaySession::class.java))
            val generation: AtomicInteger = ReflectionHelpers.getField(controller, "wirelessGeneration")
            generation.incrementAndGet()
            listener.onVideoFrameRendered(session)
            assertTrue(remembered.isEmpty())
        }
    }

    @Test fun teardownClosesRecoveryBeforeLateSessionCallbacks() {
        withWirelessListener { controller, listener, session, remembered, policy ->
            listener.onSessionActive(session)
            controller.close()
            listener.onVideoFrameRendered(session)
            assertNull(policy.refresh(endpoint))
            assertTrue(remembered.isEmpty())
            assertTrue(controller.awaitClosed(4000))
        }
    }
}
