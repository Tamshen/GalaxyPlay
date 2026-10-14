package com.shilapi.xcertplay

import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RemoteLogArchivePerformanceTest {
    @Test fun sevenThousandEventIdentitiesKeepFullOrderAndExactDuplicateHandling() {
        val app = RuntimeEnvironment.getApplication()
        RemoteLogUpload.cancel()
        L7DebugLog.buffer.clear()
        val logs = File(app.filesDir, "logs").apply { deleteRecursively(); mkdirs() }
        val lines = (0 until 7000).map { index ->
            DiagnosticLogIdentity.prefix(DiagnosticLogIdentity.next(), 1000L + index) +
                "perf_event_index=$index Connection stage=RunningWireless token=private-test-secret " +
                "counter=1 codec=H264 ".repeat(10)
        }
        File(logs, "previous-7.log").writeText(lines.joinToString("\n"))
        File(logs, "diplay.log").writeText(lines.take(500).joinToString("\n"))
        val began = System.nanoTime()
        try {
            RemoteLogArchive.collect(app).use { report ->
                val prepared = System.nanoTime()
                assertEquals(500, report.duplicateCopies)
                assertEquals(0, report.omittedLines)
                val indexes = mutableListOf<Int>()
                var bytes = 0L
                val pattern = Regex("perf_event_index=(\\d+)")
                for (batch in report.batches) {
                    val body = batch.body
                    bytes += body.size
                    assertTrue(body.size <= RemoteLogReport.MAX_BODY)
                    assertFalse(body.toString(Charsets.UTF_8).contains("private-test-secret"))
                    batch.entries.forEach { entry -> pattern.find(entry.message)?.let {
                        indexes += it.groupValues[1].toInt()
                    } }
                }
                assertEquals((0 until 7000).toList(), indexes)
                println("LOG_ARCHIVE_PERF lines=7000 duplicates=500 prepareMs=${(prepared - began) / 1_000_000} " +
                    "readMs=${(System.nanoTime() - prepared) / 1_000_000} batches=${report.batches.size} bytes=$bytes")
            }
        } finally {
            RemoteLogUpload.cancel()
            L7DebugLog.buffer.clear()
            logs.deleteRecursively()
        }
    }
}
