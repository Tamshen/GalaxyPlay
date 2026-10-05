package com.shilapi.xcertplay

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class L7DebugLogPersistenceTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val log get() = File(app.filesDir, "logs/debug.log")

    @After fun cleanup() {
        assertTrue(AsyncDiagnosticLog.awaitIdle(2000))
        ReflectionHelpers.setStaticField(L7DebugLog::class.java, "persistent", null)
        L7DebugLog.buffer.clear()
    }

    @Test fun defaultDebugEvidenceSurvivesMemoryClearAndIsIncludedWithoutStartingUpload() {
        val status = RemoteLogUpload.status
        L7DebugLog.initialize(app)
        L7DebugLog.record("AUDIO_TEST stage=START source=6 sampleRate=16000")
        L7DebugLog.record("AUDIO_TEST peer=192.168.49.1 token=private-key payload=private-speech")
        assertTrue(AsyncDiagnosticLog.awaitIdle(2000))
        L7DebugLog.buffer.clear()
        assertTrue(log.readText().contains("AUDIO_TEST stage=START"))
        assertFalse(log.readText().contains("private"))
        val report = RemoteLogReport.collect(app)
        assertTrue(report.batches.flatMap { it.entries }.any {
            it.source == "debug.log" && "AUDIO_TEST stage=START" in it.message
        })
        assertEquals(status, RemoteLogUpload.status)
    }

    @Test fun collectingFactsAlsoQueuesTheUnderlyingLogRecords() {
        val report = L7ProbeReport("synthetic-debug-batch", "generation", "test", 1,
            L7ProbePhase.COMPLETED, listOf(L7ProbeItem("ENV-SYSTEM", "system", "ENVIRONMENT",
                L7ProbeOutcome.VERIFIED, "QUERY_ONLY", mapOf("api" to "30"))), 1, 2)
        L7ProbeLog.write(app, report)
        assertTrue(AsyncDiagnosticLog.awaitIdle(2000))
        val text = log.readText()
        assertTrue(text.contains("entry=ENV-SYSTEM status=SUPPORTED"))
        assertTrue(text.contains("api=30"))
        assertTrue(text.contains("event=end phase=COMPLETED"))
    }

    @Test fun normalFullBatchKeepsEveryDetailInTheUploadCollectionBeyondTheOldBudget() {
        val items = (1..185).map { index -> L7ProbeItem("PERM:example.$index", "example.$index", "PERMISSION",
            L7ProbeOutcome.OBSERVED, "SPECIAL_ACCESS_ALLOWED", mapOf("granted" to "false", "specialAccess" to "ALLOWED",
                "specialAccessMethod" to "Settings.System.canWrite", "protectionBase" to "SIGNATURE",
                "protectionFlags" to "PRIVILEGED", "model" to "synthetic".repeat(50))) }
        val report = L7ProbeReport("00000000-0000-0000-0000-000000000002", "generation", "test", 1,
            L7ProbePhase.COMPLETED, items, items.size, 2)
        L7ProbeLog.write(app, report)
        assertTrue(AsyncDiagnosticLog.awaitIdle(2000))
        val saved = File(app.filesDir, "logs/probe-latest.log").readText()
        assertTrue(saved.toByteArray().size > 80 * 1024)
        assertFalse(saved.contains("event=truncated"))
        L7DebugLog.buffer.clear()
        val upload = RemoteLogReport.collect(app).batches.flatMap { it.entries }
            .filter { it.source == "probe-latest.log" }.joinToString("\n") { it.message }
        items.indices.forEach { index ->
            assertTrue(upload.contains("item=$index entry=PERM:example.${index + 1} status=GRANTED"))
            assertTrue(upload.contains("item=$index entry=PERM:example.${index + 1} detail"))
        }
        assertTrue(upload.contains("specialAccess=ALLOWED"))
        assertTrue(upload.contains("event=end phase=COMPLETED"))
    }

    @Test fun clearingReportsKeepsDebugEvidenceAndClearingLogsAllowsNewEvidence() {
        L7DebugLog.initialize(app)
        L7DebugLog.record("AUDIO_TEST run=1 stage=STOP")
        assertTrue(AsyncDiagnosticLog.awaitIdle(2000))
        val report = L7ProbeReport("00000000-0000-0000-0000-000000000001", "generation", "test", 1,
            L7ProbePhase.COMPLETED, emptyList(), 0, 2)
        L7ProbeStore(File(app.filesDir, "probe-reports")).save(report)
        assertTrue(L7ProbeRunner.clear(app, false) {})
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (L7ProbeRunner.busy && System.nanoTime() < until) Thread.sleep(5)
        assertFalse(L7ProbeRunner.busy)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(log.readText().contains("run=1"))
        assertTrue(L7ProbeStore(File(app.filesDir, "probe-reports")).load().isEmpty())
        AsyncDiagnosticLog.clear(File(app.filesDir, "logs"))
        assertFalse(log.exists())
        L7DebugLog.record("AUDIO_TEST run=2 stage=START")
        assertTrue(AsyncDiagnosticLog.awaitIdle(2000))
        assertTrue(log.readText().contains("run=2"))
        assertFalse(log.readText().contains("run=1"))
    }
}
