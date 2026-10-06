package com.shilapi.xcertplay

import com.shilapi.xcertplay.diagnostics.DiagnosticChannel
import com.shilapi.xcertplay.diagnostics.DiagnosticEvent
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class GalaxyDiagnosticSinkTest {
    @Test fun lateOwnerCannotWriteIntoNextSessionAndFieldsRemainBoundedMetadata() {
        val lines = mutableListOf<String>()
        var current = true
        val channel = DiagnosticChannel(42, GalaxyDiagnosticSink(lines::add) { current }) { 0 }
        channel.emit(DiagnosticEvent.Component.VIDEO, DiagnosticEvent.Kind.RECOVERY,
            DiagnosticEvent.State.REQUESTED, mapOf("attempt" to 4L))
        current = false
        channel.emit(DiagnosticEvent.Component.VIDEO, DiagnosticEvent.Kind.PRESENTATION,
            DiagnosticEvent.State.READY)
        assertEquals(listOf("CORE_TRACE session=42 seq=1 elapsedMs=0 component=VIDEO kind=RECOVERY state=REQUESTED attempt=4"), lines)
    }

    @Test fun structuredTraceUsesExistingUploadableFileQueueAndClearGeneration() {
        val directory = Files.createTempDirectory("galaxy-log").toFile()
        val target = SessionLogFile(directory.resolve("debug.log"))
        try {
            val channel = DiagnosticChannel(1, GalaxyDiagnosticSink({ L7DebugLog.record(it, target) }))
            channel.emit(DiagnosticEvent.Component.CONNECTION, DiagnosticEvent.Kind.START,
                DiagnosticEvent.State.REQUESTED)
            assertTrue(AsyncDiagnosticLog.awaitIdle(2000))
            assertTrue(target.file.readText().contains("CORE_TRACE session=1"))
            assertTrue(L7DebugLog.buffer.snapshot().lines.any { it.contains("CORE_TRACE session=1") })
            assertTrue("debug.log" in SessionLogFile.REPORT_NAMES)
            AsyncDiagnosticLog.clear(directory)
            channel.finish()
            assertTrue(AsyncDiagnosticLog.awaitIdle(2000))
            val saved = target.file.readText()
            assertFalse(saved.contains("kind=START"))
            assertTrue(saved.contains("kind=STOP state=REQUESTED"))
        } finally {
            channelCleanup(target, directory)
        }
    }

    private fun channelCleanup(target: SessionLogFile, directory: java.io.File) {
        target.close()
        directory.deleteRecursively()
        L7DebugLog.buffer.clear()
    }
}
