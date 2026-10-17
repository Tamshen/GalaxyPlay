package com.shilapi.xcertplay

import java.nio.file.Files
import java.lang.management.ManagementFactory
import org.junit.Assert.*
import org.junit.Test

class DiagnosticBatchWriteTest {
    @Test fun batchPreservesOrderAndRotatesAtTheOriginalUtf8ByteBoundary() {
        val dir = Files.createTempDirectory("batch-rotate").toFile()
        val log = SessionLogFile(dir.resolve("current.log"), listOf("old.log"))
        try {
            val lines = (1..3).map { "stage=$it " + "字".repeat(70_000) }
            log.appendRedactedBatch(lines, null)
            assertEquals(lines.take(2).joinToString("\n", postfix = "\n"), dir.resolve("old.log").readText())
            assertEquals(lines.last() + "\n", log.file.readText())
            log.close(); log.appendRedactedBatch(listOf("late"), null)
            assertFalse(log.file.readText().contains("late"))
        } finally { log.close(); dir.deleteRecursively() }
    }

    @Test fun clearInvalidatesOldQueuedBatchAndWriteFailureDoesNotStopTheNextTarget() {
        val dir = Files.createTempDirectory("batch-clear").toFile()
        val log = SessionLogFile(dir.resolve("debug.log"))
        val notFolder = dir.resolve("not-folder").apply { writeText("file") }
        val failed = SessionLogFile(notFolder.resolve("failed.log"))
        try {
            synchronized(SessionLogFile.storageLock) {
                AsyncDiagnosticLog.append(log, "old-generation")
                AsyncDiagnosticLog.clear(dir)
                AsyncDiagnosticLog.append(failed, "cannot-write")
                AsyncDiagnosticLog.append(log, "new-generation token=secret-value peer=192.0.2.1", eventId = "token=unsafe-prefix")
            }
            assertTrue(AsyncDiagnosticLog.awaitIdle(2000))
            val text = log.file.readText()
            assertFalse(text.contains("old-generation")); assertTrue(text.contains("new-generation"))
            assertFalse(text.contains("secret-value")); assertFalse(text.contains("unsafe-prefix"))
            assertFalse(text.contains("192.0.2.1"))
        } finally { failed.close(); log.close(); dir.deleteRecursively() }
    }

    @Test fun trustedBatchCostComparisonKeepsTheSameCompleteRecords() {
        val dir = Files.createTempDirectory("batch-cost").toFile()
        val thread = ManagementFactory.getThreadMXBean()
        val allocation = thread as? com.sun.management.ThreadMXBean
        val lines = (1..4000).map { "PERFORMANCE stage=SAMPLE sequence=$it elapsedMs=1000 cpuCorePercent=35" }
        try {
            for ((run, batch) in listOf(false, true, false, true).withIndex()) {
                val log = SessionLogFile(dir.resolve("cost-$run.log"))
                val startCpu = thread.currentThreadCpuTime
                val startAllocation = allocation?.getThreadAllocatedBytes(Thread.currentThread().id) ?: 0
                val start = System.nanoTime()
                if (batch) lines.chunked(16).forEach { log.appendRedactedBatch(it, null) }
                else lines.forEach { log.append(it) }
                println("LOG_COST batch=$batch records=${lines.size} wallNs=${System.nanoTime()-start} " +
                    "threadCpuNs=${thread.currentThreadCpuTime-startCpu} " +
                    "allocatedBytes=${(allocation?.getThreadAllocatedBytes(Thread.currentThread().id) ?: 0)-startAllocation}")
                assertEquals(lines, log.file.readLines())
                log.close()
            }
        } finally { dir.deleteRecursively() }
    }
}
