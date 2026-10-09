package com.shilapi.xcertplay.media

import android.media.MediaFormat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaCodecList
import org.robolectric.shadows.MediaCodecInfoBuilder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class AudioCodecCapabilitiesTest {
    private fun add(name: String, encoder: Boolean, mime: String = MediaFormat.MIMETYPE_AUDIO_OPUS) {
        val caps = MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(MediaFormat.createAudioFormat(mime, 48_000, 1)).setIsEncoder(encoder).build()
        val info = MediaCodecInfoBuilder.newBuilder().setName(name).setIsEncoder(encoder)
            .setCapabilities(caps).build()
        ShadowMediaCodecList.addCodec(info)
    }

    @Test fun missingDecoderAndEncoderDoNotAdvertiseOpus() {
        assertFalse(AudioCodecCapabilities.opusOutputAvailable())
        assertFalse(AudioCodecCapabilities.opusInputAvailable())
    }

    @Test fun decoderAvailabilityCannotAdvertiseAnAbsentEncoder() {
        add("decoder", false)
        assertTrue(AudioCodecCapabilities.opusOutputAvailable())
        assertFalse(AudioCodecCapabilities.opusInputAvailable())
        assertEquals(listOf("decoder"), AudioCodecCapabilities.opusCandidates(false))
    }

    @Test fun unrelatedCodecAndDuplicateNameDoNotReplaceAWorkingAlternative() {
        add("aac", false, MediaFormat.MIMETYPE_AUDIO_AAC)
        add("working", false)
        add("working", false)
        add("encoder", true)
        assertEquals(listOf("working"), AudioCodecCapabilities.opusCandidates(false))
        assertEquals(listOf("encoder"), AudioCodecCapabilities.opusCandidates(true))
        assertTrue(AudioCodecCapabilities.opusInputAvailable())
    }
}
