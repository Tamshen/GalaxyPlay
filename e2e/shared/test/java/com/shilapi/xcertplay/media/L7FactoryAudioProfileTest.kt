package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30], manifest = Config.NONE)
class L7FactoryAudioProfileTest {
    @Test fun oemRingtoneUsesSonificationWithoutChangingSpeechRoles() {
        val profile = L7FactoryAudioProfile(null) { _, _ -> null }
        val ring = profile.attributes(AudioChannel.RINGTONE, AudioAttributes.CONTENT_TYPE_SPEECH, 0)
        assertEquals(AudioAttributes.USAGE_NOTIFICATION_RINGTONE, ring.usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_SONIFICATION, ring.contentType)
        for (role in listOf(AudioOutputRole.PHONE, AudioOutputRole.ASSISTANT, AudioOutputRole.NAVIGATION))
            assertEquals(AudioAttributes.CONTENT_TYPE_SPEECH, profile.attributes(role, AudioOutputPolicy.BUILTIN).contentType)
    }
    @Test fun standardChoicesAndDefaultCallsIgnoreVendorRemapping() {
        val profile = L7FactoryAudioProfile(null) { _, _ -> 4 }
        assertEquals(1, profile.attributes(AudioOutputRole.MEDIA, AudioOutputPolicy.MEDIA).usage)
        assertEquals(12, profile.attributes(AudioOutputRole.NAVIGATION, AudioOutputPolicy.NAVIGATION).usage)
        assertEquals(16, profile.attributes(AudioOutputRole.ASSISTANT, AudioOutputPolicy.ASSISTANT).usage)
        assertEquals(2, profile.attributes(AudioChannel.PHONE, AudioAttributes.CONTENT_TYPE_SPEECH, 0).usage)
        assertEquals(1, profile.attributes(AudioOutputRole.PHONE, AudioOutputPolicy.MEDIA).usage)
        assertEquals(12, profile.attributes(AudioOutputRole.PHONE, AudioOutputPolicy.NAVIGATION).usage)
        assertEquals(2, profile.attributes(AudioOutputRole.PHONE, AudioOutputPolicy.PHONE).usage)
        assertEquals(4, profile.attributes(AudioOutputRole.NAVIGATION, 0).usage)
    }
    @Test fun mediaTemplateCannotCollapseSpeechOrNavigationIntoMusic() {
        val profile = L7FactoryAudioProfile(JSONObject("""{"AudioUsage":{"AUDIO_USAGE_CP_MEDIA":1,"AUDIO_USAGE_CP_GUIDANCE":1,"AUDIO_USAGE_CP_SIRI":1}}""")) { _, _ -> null }
        assertEquals(1, profile.usage(AudioChannel.MEDIA))
        assertEquals(12, profile.usage(AudioChannel.NAVIGATION))
        assertEquals(16, profile.usage(AudioChannel.ASSISTANT))
        assertEquals(6, profile.usage(AudioChannel.RINGTONE))
    }

    @Test fun frameworkOverridesConfigButRejectedVendorUsageFallsBackToStandard() {
        val profile = L7FactoryAudioProfile(JSONObject("""{"AudioUsage":{"AUDIO_USAGE_CP_SIRI":12}}""")) { _, _ -> 40001 }
        assertEquals(40001, profile.usage(AudioChannel.ASSISTANT))
        assertEquals(16, profile.attributes(AudioOutputRole.ASSISTANT, 0).usage)
        assertEquals(1, profile.attributes(AudioOutputRole.ASSISTANT, AudioOutputPolicy.MEDIA).usage)
    }

    @Test fun wiredAndWirelessMicrophoneSourcesStaySeparateAndMissingValuesRemainOptional() {
        val profile = L7FactoryAudioProfile(JSONObject("""{"AudioSource":{"AUDIO_SOURCE_CP_SIRI":40,"AUDIO_SOURCE_WIRELESS_CP_SIRI":41,"AUDIO_SOURCE_CP_PHONE_SWB":42,"AUDIO_SOURCE_CP_PHONE_NB":1}}""")) { _, _ -> null }
        assertEquals(40, profile.microphoneSource("speechRecognition", 24000, false))
        assertEquals(41, profile.microphoneSource("speechRecognition", 24000, true))
        assertEquals(42, profile.microphoneSource("telephony", 24000, false))
        assertNull(profile.microphoneSource("telephony", 8000, false))
        assertNull(profile.microphoneSource("media", 48000, false))
    }
}
