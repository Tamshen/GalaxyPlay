package com.shilapi.xcertplay

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class L7SteeringLogTest {
    @Test fun steeringRotationDoesNotOverwriteConnectionHistoryAndClearIncludesBoth() {
        val directory = Files.createTempDirectory("l7-steering-log").toFile()
        val steering = SessionLogFile(directory.resolve("steering.log"),
            listOf("steering-previous.log", "steering-previous-2.log"))
        try {
            directory.resolve("previous.log").writeText("connection evidence")
            steering.file.writeText("x".repeat(SessionLogFile.MAX_BYTES.toInt() - 1))
            steering.append("STEERING_TRACE trace=1 stage=CHANNEL_WRITTEN")
            assertEquals("connection evidence", directory.resolve("previous.log").readText())
            assertTrue(directory.resolve("steering-previous.log").exists())
            assertTrue(steering.file.readText().contains("CHANNEL_WRITTEN"))
            AsyncDiagnosticLog.clear(directory)
            assertFalse(directory.resolve("steering-previous.log").exists())
            assertFalse(directory.resolve("previous.log").exists())
            AsyncDiagnosticLog.append(steering, "STEERING_TRACE trace=2 stage=MARK detail=USER_FAILURE")
            assertTrue(AsyncDiagnosticLog.awaitIdle(2000))
            assertTrue(steering.file.readText().contains("USER_FAILURE"))
        } finally { steering.close(); directory.deleteRecursively() }
    }
}
