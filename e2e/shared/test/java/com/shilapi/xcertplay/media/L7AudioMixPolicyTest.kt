package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class L7AudioMixPolicyTest {
    @Test fun phoneWinsOverMediaAndSiri() {
        val active = listOf(AudioChannel.MEDIA, AudioChannel.PHONE, AudioChannel.ASSISTANT)
        assertEquals(AudioChannel.PHONE, active.maxByOrNull(L7AudioMixPolicy::priority))
        assertEquals(0f, L7AudioMixPolicy.volume(AudioChannel.MEDIA, active, true, false), 0f)
        assertEquals(0f, L7AudioMixPolicy.volume(AudioChannel.ASSISTANT, active, true, false), 0f)
        assertEquals(1f, L7AudioMixPolicy.volume(AudioChannel.PHONE, active, true, false), 0f)
    }

    @Test fun guidanceDucksOnlyOwnMediaAndRestoresAfterEnding() {
        val active = listOf(AudioChannel.MEDIA, AudioChannel.NAVIGATION)
        assertEquals(0.2f, L7AudioMixPolicy.volume(AudioChannel.MEDIA, active, true, false), 0f)
        assertEquals(1f, L7AudioMixPolicy.volume(AudioChannel.NAVIGATION, active, true, false), 0f)
        assertEquals(1f, L7AudioMixPolicy.volume(AudioChannel.MEDIA, listOf(AudioChannel.MEDIA), true, false), 0f)
    }

    @Test fun focusDenialOrLossSilencesEveryChannelAndGainRestores() {
        for (channel in AudioChannel.entries) {
            assertEquals(0f, L7AudioMixPolicy.volume(channel, listOf(channel), false, false), 0f)
            assertEquals(0.2f, L7AudioMixPolicy.volume(channel, listOf(channel), true, true), 0f)
            assertEquals(1f, L7AudioMixPolicy.volume(channel, listOf(channel), true, false), 0f)
        }
    }

    @Test fun siriWinsOverGuidanceAndDucksMedia() {
        val active = listOf(AudioChannel.MEDIA, AudioChannel.NAVIGATION, AudioChannel.ASSISTANT)
        assertEquals(0f, L7AudioMixPolicy.volume(AudioChannel.NAVIGATION, active, true, false), 0f)
        assertEquals(0.2f, L7AudioMixPolicy.volume(AudioChannel.MEDIA, active, true, false), 0f)
        assertEquals(1f, L7AudioMixPolicy.volume(AudioChannel.ASSISTANT, active, true, false), 0f)
    }
}
