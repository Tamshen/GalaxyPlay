package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class L7AudioRoutePolicyTest {
    @Test fun selectsCurrentIdAndDirectionWithoutHardcodingDeviceIds() {
        val devices = listOf(L7AudioDevice(73, " bus00_media ", false), L7AudioDevice(9, "BUS00_MEDIA", true))
        assertEquals(73, L7AudioRoutePolicy.select(devices, AudioChannel.MEDIA, false, 48_000, 2)?.id)
        assertNull(L7AudioRoutePolicy.select(devices, AudioChannel.MEDIA, true, 48_000, 2))
        assertNull(L7AudioRoutePolicy.select(emptyList(), AudioChannel.MEDIA, false, 48_000, 2))
    }

    @Test fun rejectsUnsupportedFormatsAndAmbiguousBusInstances() {
        val device = L7AudioDevice(73, "BUS00_MEDIA", false, listOf(48_000), listOf(2))
        assertNull(L7AudioRoutePolicy.select(listOf(device), AudioChannel.MEDIA, false, 44_100, 2))
        assertNull(L7AudioRoutePolicy.select(listOf(device), AudioChannel.MEDIA, false, 48_000, 1))
        assertNull(L7AudioRoutePolicy.select(listOf(device, device.copy(id = 74)), AudioChannel.MEDIA, false, 48_000, 2))
        assertNotNull(L7AudioRoutePolicy.select(listOf(device), AudioChannel.MEDIA, false, 48_000, 2))
    }

    @Test fun phoneBandsHaveSeparateInputAndOutputCandidates() {
        val bands = listOf(8_000 to "NB", 16_000 to "WB", 32_000 to "SWB", 48_000 to "FB")
        for ((rate, band) in bands) {
            assertTrue(L7AudioRoutePolicy.candidate(AudioChannel.PHONE, true, rate)!!.endsWith("${band}_UP"))
            assertTrue(L7AudioRoutePolicy.candidate(AudioChannel.PHONE, false, rate)!!.endsWith("${band}_DL"))
        }
        assertNull(L7AudioRoutePolicy.candidate(AudioChannel.PHONE, false, 24_000))
        assertNull(L7AudioRoutePolicy.candidate(AudioChannel.PHONE, false, 44_100))
    }

    @Test fun siriInputIsExplicitButNotificationOutputIsNotGuessed() {
        assertEquals("BUS20_CARPLAY_SIRI_UL", L7AudioRoutePolicy.candidate(AudioChannel.ASSISTANT, true, 24_000))
        assertNull(L7AudioRoutePolicy.candidate(AudioChannel.ASSISTANT, false, 24_000))
        assertNull(L7AudioRoutePolicy.candidate(AudioChannel.NAVIGATION, false, 48_000))
    }

    @Test fun removedDeviceCannotLeaveAnOldIdInSelection() {
        val old = L7AudioDevice(17, "BUS00_MEDIA", false)
        assertEquals(17, L7AudioRoutePolicy.select(listOf(old), AudioChannel.MEDIA, false, 48_000, 2)?.id)
        assertEquals(91, L7AudioRoutePolicy.select(listOf(old.copy(id = 91)), AudioChannel.MEDIA, false, 48_000, 2)?.id)
    }
}
