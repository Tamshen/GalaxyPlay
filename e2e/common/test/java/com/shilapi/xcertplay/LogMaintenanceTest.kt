package com.shilapi.xcertplay

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class LogMaintenanceTest {
    @Test fun clearDropsQueuedOldLinesAndActiveSessionCanContinue() {
        val directory = Files.createTempDirectory("l7-log-clear").toFile()
        try {
            val file = SessionLogFile(directory.resolve("diplay.log"))
            file.reset("old session")
            directory.resolve("previous-7.log").writeText("old retained")
            directory.resolve("unrelated.txt").writeText("keep")
            synchronized(SessionLogFile.storageLock) {
                repeat(10) { AsyncDiagnosticLog.append(file, "old queued event=$it") }
                AsyncDiagnosticLog.clear(directory)
            }
            AsyncDiagnosticLog.append(file, "new session event=ready")
            assertTrue(AsyncDiagnosticLog.awaitIdle(2000))
            assertFalse(file.file.readText().contains("old"))
            assertTrue(file.file.readText().contains("new session event=ready"))
            assertFalse(directory.resolve("previous-7.log").exists())
            assertEquals("keep", directory.resolve("unrelated.txt").readText())
            file.close()
        } finally { directory.deleteRecursively() }
    }
}
