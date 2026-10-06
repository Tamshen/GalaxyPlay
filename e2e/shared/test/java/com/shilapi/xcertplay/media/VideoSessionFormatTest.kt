package com.shilapi.xcertplay.media

import android.media.MediaFormat
import org.junit.Assert.*
import org.junit.Test

class VideoSessionFormatTest {
    @Test fun unsupportedRequestedFormatUsesOnlyConfirmedHardwareAvcAtThirty() {
        val queries = mutableListOf<Triple<String, Int, Boolean>>()
        val result = VideoSessionFormat.select(true, 60, true) { mime, fps, software ->
            queries.add(Triple(mime, fps, software))
            mime == MediaFormat.MIMETYPE_VIDEO_AVC && fps == 30 && !software
        }
        assertEquals(VideoSessionFormat(false, 30), result)
        assertEquals(listOf(Triple(MediaFormat.MIMETYPE_VIDEO_HEVC, 60, true),
            Triple(MediaFormat.MIMETYPE_VIDEO_AVC, 30, false)), queries)
    }
    @Test fun unknownCapabilitiesAndUnavailableFallbackPreserveRequestedFormat() {
        assertEquals(VideoSessionFormat(true, 60), VideoSessionFormat.select(true, 60, true) { _, _, _ -> null })
        assertEquals(VideoSessionFormat(true, 60), VideoSessionFormat.select(true, 60, true) { _, _, _ -> false })
        assertEquals(VideoSessionFormat(true, 60), VideoSessionFormat.select(true, 60, true) { _, _, _ -> true })
    }
}
