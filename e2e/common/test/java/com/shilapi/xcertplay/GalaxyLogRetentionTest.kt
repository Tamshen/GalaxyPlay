package com.shilapi.xcertplay

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class GalaxyLogRetentionTest {
    @Test fun globalBudgetDeletesOldestArchivesAndKeepsActiveLogs() {
        val root = Files.createTempDirectory("galaxy-log-budget").resolve("logs").toFile().apply { mkdirs() }
        try {
            SessionLogFile.REPORT_NAMES.forEachIndexed { index, name ->
                File(root, name).apply { writeBytes(ByteArray(512_000)); setLastModified(index + 1L) }
            }
            GalaxyLogRetention(root).prune()
            assertTrue(root.listFiles()!!.sumOf { it.length() } <= GalaxyLogRetention.MAX_BYTES)
            assertFalse(File(root, "previous-7.log").exists())
            assertTrue(File(root, "diplay.log").exists())
            assertTrue(File(root, "debug.log").exists())
        } finally { root.parentFile.deleteRecursively() }
    }

    @Test fun continuedWritesAndRotationsNeverExceedFiveMBAndDoNotTouchOtherData() {
        val root = Files.createTempDirectory("galaxy-log-writing").resolve("logs").toFile().apply { mkdirs() }
        val old = SessionLogFile.storageChanged
        try {
            val retention = GalaxyLogRetention(root)
            SessionLogFile.storageChanged = retention::changed
            val settings = File(root, "settings.json").apply { writeText("keep") }
            SessionLogFile(File(root, "diplay.log")).use { log ->
                repeat(2500) { log.append("event=$it detail=" + "counter ".repeat(450)) }
            }
            assertTrue(root.listFiles()!!.filter { it.extension == "log" }.sumOf { it.length() } <= GalaxyLogRetention.MAX_BYTES)
            assertEquals("keep", settings.readText())
            assertTrue(File(root, "diplay.log").readText().contains("event=2499"))
            root.listFiles()!!.filter { it.extension == "log" && it.name != "diplay.log" }.forEach { it.delete() }
            val recent = File(root, "debug.log").apply { writeText("new evidence") }
            SessionLogFile(File(root, "diplay.log")).use { it.append("after manual clear") }
            assertTrue(recent.exists())
        } finally { SessionLogFile.storageChanged = old; root.parentFile.deleteRecursively() }
    }

    @Test fun abnormalLargeActiveFileIsTrimmedToRecentLinesWithoutFollowingSymlinks() {
        val root = Files.createTempDirectory("galaxy-log-large").resolve("logs").toFile().apply { mkdirs() }
        try {
            val active = File(root, "diplay.log").apply { writeText("old\n".repeat(1_500_000) + "last evidence\n") }
            val outside = File(root.parentFile, "outside.log").apply { writeText("private") }
            Files.createSymbolicLink(File(root, "debug.log").toPath(), outside.toPath())
            GalaxyLogRetention(root).prune()
            assertTrue(active.length() <= SessionLogFile.MAX_BYTES)
            assertTrue(active.readText().endsWith("last evidence\n"))
            assertEquals("private", outside.readText())
        } finally { root.parentFile.deleteRecursively() }
    }
}
