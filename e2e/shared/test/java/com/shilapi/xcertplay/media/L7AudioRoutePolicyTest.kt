package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class L7AudioRoutePolicyTest {
    private val template get() = AudioRoutingTemplate.parse("""{
        "version":1,"name":"L7 BUS","preferBus":true,
        "choices":{"media":101,"navigation":103,"assistant":102},
        "outputBuses":{"media":"bus0_media_out","navigation":"bus1_navigation_out",
        "assistant":"bus2_voice_command_out","phone":"bus4_call_out","ringtone":"bus3_call_ring_out"}
    }""")

    @Test fun observedNavigationBusIsVisibleButPrivateAddressesRemainRedacted() {
        assertTrue(L7AudioRoutePolicy.knownBus("bus1_navigation_out"))
        assertFalse(L7AudioRoutePolicy.knownBus("01:23:45:67:89:ab"))
        assertFalse(L7AudioRoutePolicy.knownBus("private-headrest-device"))
        assertNull(L7AudioRoutePolicy.candidate(AudioChannel.NAVIGATION, false, 48_000))
        assertEquals("bus1_navigation_out", L7AudioRoutePolicy.candidate(AudioChannel.NAVIGATION, false, 48_000, template))
    }

    @Test fun selectsCurrentIdAndDirectionWithoutHardcodingDeviceIds() {
        val devices = listOf(L7AudioDevice(73, " bus0_media_out ", false), L7AudioDevice(9, "bus0_media_out", true),
            L7AudioDevice(20, "BUS00_MEDIA", false))
        assertEquals(73, select(devices)?.id)
        assertNull(L7AudioRoutePolicy.select(devices, AudioChannel.MEDIA, true, 48_000, 2, template))
        assertNull(select(emptyList()))
    }

    @Test fun rejectsUnsupportedFormatsAndAmbiguousBusInstances() {
        val device = L7AudioDevice(73, "bus0_media_out", false, listOf(48_000), listOf(2))
        assertNull(select(listOf(device), rate = 44_100))
        assertNull(select(listOf(device), channels = 1))
        assertNull(select(listOf(device, device.copy(id = 74))))
        assertNotNull(select(listOf(device)))
    }

    @Test fun phoneAndAssistantInputAreNeverGuessedFromSampleRate() {
        for (rate in listOf(8_000, 16_000, 24_000, 32_000, 44_100, 48_000)) {
            assertNull(L7AudioRoutePolicy.candidate(AudioChannel.PHONE, true, rate, template))
            assertNull(L7AudioRoutePolicy.candidate(AudioChannel.ASSISTANT, true, rate, template))
            assertEquals("bus4_call_out", L7AudioRoutePolicy.candidate(AudioChannel.PHONE, false, rate, template))
        }
    }

    @Test fun removedDeviceCannotLeaveAnOldIdInSelection() {
        val old = L7AudioDevice(17, "bus0_media_out", false)
        assertEquals(17, select(listOf(old))?.id)
        assertEquals(91, select(listOf(old.copy(id = 91)))?.id)
    }

    private fun select(devices: List<L7AudioDevice>, rate: Int = 48_000, channels: Int = 2) =
        L7AudioRoutePolicy.select(devices, AudioChannel.MEDIA, false, rate, channels, template)
}
