package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class AudioOutputPolicyTest {
    @Test fun builtinDefaultsSeparateMediaAssistantAndNavigation() {
        assertEquals(1, AudioOutputPolicy.usage(AudioOutputRole.MEDIA, 0))
        assertEquals(16, AudioOutputPolicy.usage(AudioOutputRole.ASSISTANT, 0))
        assertEquals(12, AudioOutputPolicy.usage(AudioOutputRole.NAVIGATION, 0))
        assertEquals(2, AudioOutputPolicy.usage(AudioChannel.PHONE, 101))
    }

    @Test fun overridingAssistantOutputDoesNotChangeItsProtocolRole() {
        val selection = AudioChannelMapper.map("speechRecognition", 100, AudioChannelMappingMode.MOBILE_COMPATIBLE)
        assertEquals(1, AudioOutputPolicy.usage(selection.channel, 101))
        assertEquals(AudioChannel.MEDIA, AudioOutputPolicy.routingChannel(selection.channel, 101))
        assertEquals(AudioChannel.ASSISTANT, selection.channel)
        assertEquals(AudioChannel.PHONE, AudioOutputPolicy.routingChannel(AudioChannel.PHONE, 101))
    }

    @Test fun oldLegacyChoicesAndNewPresetsCannotCollide() {
        assertEquals(24, AudioOutputPolicy.choices.distinct().size)
        (1..20).forEach { assertTrue(AudioOutputPolicy.valid(it)); assertTrue(AudioOutputPolicy.isLegacy(it)) }
        (101..103).forEach { assertTrue(AudioOutputPolicy.valid(it)); assertFalse(AudioOutputPolicy.isLegacy(it)) }
        assertFalse(AudioOutputPolicy.valid(21))
        assertFalse(AudioOutputPolicy.valid(-1))
    }
}
