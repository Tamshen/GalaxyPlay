package com.shilapi.xcertplay.media

import android.media.AudioRouting
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class L7AudioRoutingLifecycleTest {
    private class RouteProbe {
        var preferredCalls = 0
        var reads = 0
        var removals = 0
        var listener: AudioRouting.OnRoutingChangedListener? = null
        val routing = Proxy.newProxyInstance(AudioRouting::class.java.classLoader,
            arrayOf(AudioRouting::class.java)) { _, method, args ->
            when (method.name) {
                "setPreferredDevice" -> { assertNull(args!![0]); preferredCalls++; true }
                "getRoutedDevice" -> { reads++; null }
                "addOnRoutingChangedListener" -> { listener = args!![0] as AudioRouting.OnRoutingChangedListener; null }
                "removeOnRoutingChangedListener" -> { removals++; null }
                else -> null
            }
        } as AudioRouting
    }

    @Test fun missingBusUsesSystemRouteAndClosedBindingIgnoresLateEvents() {
        val probe = RouteProbe()
        val log = mutableListOf<String>()
        val router = L7AudioRouting(null, preferBus = true) { log.add(it) }
        val binding = router.bind(probe.routing, AudioChannel.MEDIA, false, 48_000, 2)
        assertEquals(1, probe.preferredCalls)
        assertTrue(log.any { it.contains("preferredId=-1") })
        val late = probe.listener!!
        binding.close()
        val reads = probe.reads
        late.onRoutingChanged(probe.routing)
        binding.reportActual()
        binding.close()
        assertEquals(reads, probe.reads)
        assertEquals(1, probe.removals)
        router.close()
    }

    @Test fun upstreamDefaultObservesOutputAndInputWithoutSettingPreferredDevice() {
        val router = L7AudioRouting(null) {}
        val media = RouteProbe()
        val siri = RouteProbe()
        router.bind(media.routing, AudioChannel.MEDIA, false, 48_000, 2)
        router.bind(siri.routing, AudioChannel.ASSISTANT, true, 24_000, 1)
        assertEquals(0, media.preferredCalls)
        assertEquals(0, siri.preferredCalls)
        assertTrue(media.reads > 0)
        assertTrue(siri.reads > 0)
        router.close()
        assertEquals(1, media.removals)
        assertEquals(1, siri.removals)
    }

    @Test fun sessionCloseReleasesAllListenersAndRejectsNewBindings() {
        val first = RouteProbe()
        val second = RouteProbe()
        val router = L7AudioRouting(null) {}
        router.bind(first.routing, AudioChannel.MEDIA, false, 48_000, 2)
        router.bind(second.routing, AudioChannel.ASSISTANT, true, 24_000, 1)
        router.close()
        router.close()
        assertEquals(1, first.removals)
        assertEquals(1, second.removals)
        val late = RouteProbe()
        router.bind(late.routing, AudioChannel.MEDIA, false, 48_000, 2)
        assertEquals(0, late.preferredCalls)
        assertNull(late.listener)
    }
}
