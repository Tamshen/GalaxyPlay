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
}
