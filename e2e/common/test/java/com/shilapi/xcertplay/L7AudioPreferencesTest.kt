package com.shilapi.xcertplay

import android.app.Activity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class L7AudioPreferencesTest {
    @Test fun bluetoothAutomationDefaultsOnAndCanBeDisabledWithoutChangingFocus() {
        val context = context()
        assertTrue(AirPlayPersistence.loadBluetoothMediaExclusive(context))
        AirPlayPersistence.saveAudioFocusEnabled(context, false)
        AirPlayPersistence.saveBluetoothMediaExclusive(context, false)
        assertFalse(AirPlayPersistence.loadBluetoothMediaExclusive(context))
        assertFalse(AirPlayPersistence.loadAudioFocusEnabled(context))
        AirPlayPersistence.saveBluetoothMediaExclusive(context, true)
        assertTrue(AirPlayPersistence.loadBluetoothMediaExclusive(context))
        assertFalse(AirPlayPersistence.loadAudioFocusEnabled(context))
    }
    private fun context() = Robolectric.buildActivity(Activity::class.java).setup().get()

    @Test fun newAssistantDefaultDoesNotOverwriteOldMediaAndNavigationValues() {
        val context = context()
        AirPlayPersistence.saveMediaAudioChannel(context, 3)
        AirPlayPersistence.saveNavigationAudioChannel(context, 5)
        assertEquals(0, AirPlayPersistence.loadAssistantAudioChannel(context))
        AirPlayPersistence.saveAssistantAudioChannel(context, 101)
        assertEquals(101, AirPlayPersistence.loadAssistantAudioChannel(context))
        assertEquals(3, AirPlayPersistence.loadMediaAudioChannel(context))
        assertEquals(5, AirPlayPersistence.loadNavigationAudioChannel(context))
        AirPlayPersistence.saveAssistantAudioChannel(context, 0)
        assertEquals(0, AirPlayPersistence.loadAssistantAudioChannel(context))
    }

    @Test fun presetsPersistAndInvalidOverridesFallBackToBuiltin() {
        val context = context()
        AirPlayPersistence.saveMediaAudioChannel(context, 102)
        AirPlayPersistence.saveNavigationAudioChannel(context, 103)
        assertEquals(102, AirPlayPersistence.loadMediaAudioChannel(context))
        assertEquals(103, AirPlayPersistence.loadNavigationAudioChannel(context))
        AirPlayPersistence.saveAssistantAudioChannel(context, 1999)
        assertEquals(0, AirPlayPersistence.loadAssistantAudioChannel(context))
        AirPlayPersistence.saveMediaAudioChannel(context, 45)
        assertEquals(0, AirPlayPersistence.loadMediaAudioChannel(context))
    }
}
