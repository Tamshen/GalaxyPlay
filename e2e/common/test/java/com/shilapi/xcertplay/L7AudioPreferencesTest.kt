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
    @Test fun defaultAudioMatchesUpstreamWithoutOverwritingExplicitChoices() {
        val context = context()
        assertFalse(AirPlayPersistence.loadAudioFocusEnabled(context))
        assertFalse(AirPlayPersistence.loadL7AudioBusEnabled(context))
        assertFalse(AirPlayPersistence.loadAdvancedAudioChannelMapping(context))
        assertEquals(0, AirPlayPersistence.loadMediaAudioChannel(context))
        assertEquals(0, AirPlayPersistence.loadNavigationAudioChannel(context))
        AirPlayPersistence.saveAudioFocusEnabled(context, true)
        AirPlayPersistence.saveMediaAudioChannel(context, 3)
        assertTrue(AirPlayPersistence.loadAudioFocusEnabled(context))
        assertEquals(3, AirPlayPersistence.loadMediaAudioChannel(context))
        assertFalse(AirPlayPersistence.loadL7AudioBusEnabled(context))
    }

    @Test fun restoreOnlyChangesAudioAndKeepsBluetoothAndConnectionPreferences() {
        val context = context()
        AirPlayPersistence.saveAudioFocusEnabled(context, true)
        AirPlayPersistence.saveL7AudioBusEnabled(context, true)
        AirPlayPersistence.saveAdvancedAudioChannelMapping(context, true)
        AirPlayPersistence.saveCallProcessingEnabled(context, false)
        AirPlayPersistence.saveMediaAudioChannel(context, 3)
        AirPlayPersistence.saveAssistantAudioChannel(context, 101)
        AirPlayPersistence.saveNavigationAudioChannel(context, 5)
        AirPlayPersistence.saveMediaBufferMillis(context, 1000)
        AirPlayPersistence.saveBluetoothMediaExclusive(context, false)
        AirPlayPersistence.saveWirelessEnabled(context, false)
        val prefs = context.getSharedPreferences("xcertplay_airplay", 0)
        val unrelated = prefs.all.filterKeys { it !in setOf("audio_focus_enabled", "l7_audio_bus_enabled",
            "advanced_audio_channel_mapping", "l7_call_processing_enabled", "media_audio_channel",
            "assistant_audio_channel", "navigation_audio_channel", "media_buffer_ms") }
        AirPlayPersistence.restoreUpstreamAudioDefaults(context)
        assertFalse(AirPlayPersistence.loadAudioFocusEnabled(context))
        assertFalse(AirPlayPersistence.loadL7AudioBusEnabled(context))
        assertFalse(AirPlayPersistence.loadAdvancedAudioChannelMapping(context))
        assertTrue(AirPlayPersistence.loadCallProcessingEnabled(context))
        assertEquals(0, AirPlayPersistence.loadMediaAudioChannel(context))
        assertEquals(0, AirPlayPersistence.loadAssistantAudioChannel(context))
        assertEquals(0, AirPlayPersistence.loadNavigationAudioChannel(context))
        assertEquals(300, AirPlayPersistence.loadMediaBufferMillis(context))
        unrelated.forEach { (key, value) -> assertEquals(value, prefs.all[key]) }
    }

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
