package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class L7VoiceTraceStoreTest {
    @Test fun microphoneFieldsAreWhitelistedAndOldSessionCannotOverwriteCurrent() {
        val store = L7VoiceTraceStore()
        val old = store.session()
        val current = store.session()
        store.observe(old, "Microphone: start type=telephony rate=8000")
        assertTrue(store.snapshot().microphone.isEmpty())
        store.observe(current, "Microphone: start type=speechrecognition rate=16000 routedDeviceType=15 host=secret key=private transcript=words")
        assertEquals("START", store.snapshot().microphone["phase"])
        assertEquals("15", store.snapshot().microphone["routedDeviceType"])
        assertFalse(store.snapshot().lines.single().contains("secret"))
        assertFalse(store.snapshot().lines.single().contains("private"))
        assertFalse(store.snapshot().lines.single().contains("words"))
        store.observe(current, "Microphone: stats type=speechrecognition captureBytes=320 udpSent=8 ended=true")
        assertEquals("ENDED", store.snapshot().microphone["phase"])
        assertEquals("16000", store.snapshot().microphone["rate"])
        store.observe(current, "Microphone: start type=speechrecognition rate=16000")
        assertFalse(store.snapshot().microphone.containsKey("udpSent"))
    }

    @Test fun failedStartAndStreamTypeSwitchDoNotRetainPreviousStreamCounters() {
        val store = L7VoiceTraceStore()
        val owner = store.session()
        store.observe(owner, "Audio: microphone type=telephony rms=200 packetsSent=5 ended=false")
        store.observe(owner, "Microphone: failure type=speechrecognition stage=RECORDING error=SecurityException code=-3")
        assertEquals("FAILURE", store.snapshot().microphone["phase"])
        assertFalse(store.snapshot().microphone.containsKey("packetsSent"))
    }

    @Test fun unrelatedDiagnosticsAreIgnoredAndHistoryIsBoundedAndLocallyClearable() {
        val store = L7VoiceTraceStore()
        val owner = store.session()
        store.observe(owner, "Audio: ready type=speechrecognition bufferMs=40")
        store.observe(owner, "Audio: microphone source=6 factory=false")
        assertTrue(store.snapshot().lines.isEmpty())
        repeat(50) { store.record("VOICE_TEST run=$it phase=COMPLETE") }
        assertEquals(32, store.snapshot().lines.size)
        store.observe(owner, "Microphone: start type=telephony rate=8000")
        store.clear()
        assertTrue(store.snapshot().lines.isEmpty())
        assertEquals("telephony", store.snapshot().microphone["type"])
        assertTrue(SessionLogFile.REPORT_NAMES.contains("voice-input.log"))
    }
}
