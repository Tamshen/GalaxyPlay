package com.shilapi.xcertplay.media

import android.media.MediaFormat
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
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

    @Test fun missingSystemCodecsUseBundledOpus() {
        assertTrue(AudioCodecCapabilities.opusOutputAvailable())
        assertTrue(AudioCodecCapabilities.opusInputAvailable())
    }

    @Test fun softwareFallbackPreservesOpusOfferAlongsideAacAndPcmWithoutSystemCodecs() {
        assertTrue(AudioCodecCapabilities.opusCandidates(false).isEmpty())
        assertTrue(AudioCodecCapabilities.opusCandidates(true).isEmpty())
        AudioCodecCapabilities.rejectOpusDecoder()
        AudioCodecCapabilities.rejectOpusEncoder()
        val config = AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:02", "366.0",
            AirPlayDisplayConfig(1280, 720), microphone = true,
            supportsOpusOutput = AudioCodecCapabilities.opusOutputAvailable(),
            supportsOpusInput = AudioCodecCapabilities.opusInputAvailable())
        val formats = AirPlayInfoPlist.build(config)["audioFormats"] as List<*>
        val output = formats.map { (it as Map<*, *>)["audioOutputFormats"] as Int }
        val input = formats.mapNotNull { (it as Map<*, *>)["audioInputFormats"] as? Int }
        assertTrue(output.any { it and 0x70000000 != 0 })
        assertTrue(input.any { it and 0x70000000 != 0 })
        assertTrue(output.any { it and 0x800000 != 0 })
        assertTrue(output.any { it and 0x3fc != 0 })
    }

    @Test fun systemDecoderDoesNotChangeSoftwareInputSupport() {
        add("decoder", false)
        assertTrue(AudioCodecCapabilities.opusOutputAvailable())
        assertTrue(AudioCodecCapabilities.opusInputAvailable())
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
