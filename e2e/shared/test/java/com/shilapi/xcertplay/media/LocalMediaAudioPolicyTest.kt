package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.*
import org.junit.Assert.*
import org.junit.Test

class LocalMediaAudioPolicyTest {
    private fun format(type: String) = AudioFormat(AudioCodecKind.LPCM, 48000, 2, 96, type)
    @Test fun mixedTypesPreserveVoiceAndFallbackUsesFormatRole() {
        for (type in listOf("media", "MEDIA", "default", ""))
            assertFalse(LocalMediaAudioPolicy.shouldRender(false, AudioStreamId(102, type), format(type)))
        for (type in listOf("telephony", "speechRecognition", "alert", "navigation"))
            assertTrue(LocalMediaAudioPolicy.shouldRender(false, AudioStreamId(102, type), format(type)))
        assertFalse(LocalMediaAudioPolicy.shouldRender(false, AudioStreamId(100, ""), format("media")))
        assertTrue(LocalMediaAudioPolicy.shouldRender(false, AudioStreamId(101, "default"), format("default")))
        assertTrue(LocalMediaAudioPolicy.shouldRender(true, AudioStreamId(102, "media"), format("media")))
    }
    @Test fun startAndRtpCannotRecreateFilteredMusicRenderer() {
        val changes = mutableListOf<Boolean>()
        val sink = AndroidMediaSink(localMediaAudioEnabled = false, onMediaAudioChanged = changes::add)
        val id = AudioStreamId(102, "default")
        val field = sink.javaClass.getDeclaredField("audioRenderers").apply { isAccessible = true }
        try {
            sink.onAudioStarted(id, format("default"), 0)
            sink.onAudioRtp(id, format("default"), byteArrayOf(1, 2, 3), 0)
            sink.onAudioStopped(id)
            assertTrue((field.get(sink) as Map<*, *>).isEmpty())
            assertTrue(changes.isEmpty())
        } finally { sink.close() }
        sink.onAudioRtp(id, format("default"), byteArrayOf(), 1)
        assertTrue((field.get(sink) as Map<*, *>).isEmpty())
    }
}
