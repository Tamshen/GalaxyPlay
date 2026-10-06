package com.shilapi.xcertplay.media

import android.content.Context
import android.media.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30], manifest = Config.NONE)
class NavigationOutputDeviceTest {
    @Test fun realRoutingRematchesChangingIdsAndClearsMissingOrRejectedPreference() {
        val context = mock(Context::class.java); val audio = mock(AudioManager::class.java)
        `when`(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(audio)
        fun device(id: Int) = mock(AudioDeviceInfo::class.java).apply {
            `when`(this.id).thenReturn(id); `when`(type).thenReturn(21)
            `when`(address).thenReturn("bus1_navigation_out"); `when`(productName).thenReturn("Navigation")
        }
        val first = device(7); val replacement = device(9)
        `when`(audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)).thenReturn(arrayOf(first))
        val route = mock(AudioRouting::class.java)
        `when`(route.setPreferredDevice(any())).thenReturn(true)
        val log = mutableListOf<String>()
        val router = L7AudioRouting(context, navigationDevice = NavigationOutputDevice(3, 21, "bus1_navigation_out", "Navigation"), report = log::add)
        try {
            router.bind(route, AudioChannel.NAVIGATION, false, 48000, 1, useBus = false)
            verify(route).setPreferredDevice(first)
            val callback = ArgumentCaptor.forClass(AudioDeviceCallback::class.java)
            verify(audio).registerAudioDeviceCallback(callback.capture(), any())
            `when`(audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)).thenReturn(arrayOf(replacement))
            callback.value.onAudioDevicesAdded(arrayOf(replacement))
            verify(route).setPreferredDevice(replacement)
            `when`(route.setPreferredDevice(replacement)).thenReturn(false)
            callback.value.onAudioDevicesRemoved(arrayOf(first))
            verify(route).setPreferredDevice(null)
            `when`(audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)).thenReturn(emptyArray())
            callback.value.onAudioDevicesRemoved(arrayOf(replacement))
            assertTrue(log.any { "accepted=false" in it && "preferredId=9" in it })
        } finally { router.close() }
    }
    @Test fun ambiguousAddressAndAddresslessIdentityCannotSelectArbitraryDevice() {
        val saved = NavigationOutputDevice(3, 21, "bus", "name")
        assertNull(saved.match(listOf(saved, saved.copy(id = 4))))
        assertNull(saved.copy(address = "").match(listOf(saved.copy(id = 4, address = ""))))
        assertNull(saved.copy(address = "").match(listOf(saved.copy(name = "other", address = ""))))
        assertEquals(saved.copy(address = ""), saved.copy(address = "").match(listOf(saved.copy(address = ""))))
    }
}
