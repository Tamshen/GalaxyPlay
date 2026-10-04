package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class DebugLogBufferTest {
    @Test fun evictionRetainsLatestFailureAndNeverLeaksCredentials() {
        val buffer = DebugLogBuffer(3)
        buffer.append("连接开始 peer=10.0.0.3")
        buffer.append("token=secret")
        buffer.append("payload=secret")
        buffer.append("ok\nsecret")
        buffer.append("video running")
        buffer.append("transport error: timeout")
        buffer.append("reconnecting")
        val result = buffer.snapshot()
        assertEquals(3, result.lines.size)
        assertEquals(3L, result.evicted)
        assertEquals(listOf("transport error: timeout", "reconnecting"), buffer.snapshot(onlyErrors = true).lines)
        assertFalse(result.lines.joinToString().contains("secret"))
    }

    @Test fun concurrentProducersAreBoundedAndClearInvalidatesReaderRevision() {
        val buffer = DebugLogBuffer(100)
        val writers = (0..3).map { worker -> Thread { repeat(200) { buffer.append("worker=$worker event=$it") } } }
        writers.forEach { it.start() }; writers.forEach { it.join() }
        val result = buffer.snapshot()
        assertEquals(800L, result.revision)
        assertEquals(700L, result.evicted)
        assertEquals(100, result.lines.size)
        buffer.clear()
        assertTrue(buffer.snapshot().revision > result.revision)
        assertTrue(buffer.snapshot().lines.isEmpty())
        assertEquals(0L, buffer.snapshot().evicted)
        buffer.append("disconnect")
        assertEquals(listOf("disconnect"), buffer.snapshot().lines)
    }

    @Test fun snapshotIsIndependentAndAddressesAreRedactedBeforeDisplay() {
        val buffer = DebugLogBuffer()
        buffer.append("connection peer=C0:A6:00:29:58:0A ip=10.0.0.8")
        val captured = buffer.snapshot()
        buffer.clear()
        assertEquals(1, captured.lines.size)
        assertFalse(captured.lines.single().contains("C0:A6"))
        assertFalse(captured.lines.single().contains("10.0.0.8"))
    }

    @Test fun dragAndExpansionRemainReachableAfterWindowSizeChange() {
        assertEquals(DebugOverlayPosition.Position(0, 0), DebugOverlayPosition.clamp(-20, -10, 800, 500, 1440, 1920))
        assertEquals(DebugOverlayPosition.Position(640, 1420), DebugOverlayPosition.clamp(9000, 9000, 800, 500, 1440, 1920))
        assertEquals(DebugOverlayPosition.Position(0, 600), DebugOverlayPosition.clamp(640, 1420, 800, 500, 720, 1100))
    }
}
