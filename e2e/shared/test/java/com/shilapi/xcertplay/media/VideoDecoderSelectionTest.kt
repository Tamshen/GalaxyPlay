package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class VideoDecoderSelectionTest {
    private val software = VideoDecoderCandidate("software", false, true, false)
    private val hardware = VideoDecoderCandidate("hardware", true, false, true)
    private val unknown = VideoDecoderCandidate("vendor", false, false, false)

    @Test fun defaultNeverPicksSoftwareAheadOfAnAvailableHardwareDecoder() {
        assertEquals(listOf(hardware, unknown, software),
            VideoDecoderSelection.ordered(listOf(software, unknown, hardware, hardware), false))
    }
    @Test fun explicitSoftwarePreferenceStillKeepsHardwareAsFallback() {
        assertEquals(listOf(software, hardware, unknown),
            VideoDecoderSelection.ordered(listOf(unknown, hardware, software), true))
    }

    @Test fun selectedHardwareMovesFirstWithoutRemovingDefaultFallbacks() {
        val c2 = VideoDecoderCandidate("c2.qti.avc.decoder", true, false, false)
        val omx = VideoDecoderCandidate("OMX.qcom.video.decoder.avc", true, false, false)
        assertEquals(listOf(c2, omx, unknown, software), VideoDecoderSelection.ordered(
            listOf(software, omx, unknown, c2, c2), false, c2.name))
        assertEquals(listOf(omx, c2, unknown, software), VideoDecoderSelection.ordered(
            listOf(c2, software, unknown, omx), false, omx.name))
    }

    @Test fun unavailableOrSoftwareNameKeepsDefaultSelection() {
        val candidates = listOf(unknown, software, hardware)
        val defaults = listOf(hardware, unknown, software)
        assertEquals(defaults, VideoDecoderSelection.ordered(candidates, false, "missing"))
        assertEquals(defaults, VideoDecoderSelection.ordered(candidates, false, software.name))
    }

    @Test fun hardwarePreferenceDoesNotOverrideExplicitSoftwareHevcChoice() {
        assertEquals(listOf(software, hardware, unknown), VideoDecoderSelection.ordered(
            listOf(unknown, hardware, software), true, hardware.name))
    }
}
