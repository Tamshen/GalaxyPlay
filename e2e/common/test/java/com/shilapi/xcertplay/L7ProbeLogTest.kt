package com.shilapi.xcertplay

import java.util.UUID
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class L7ProbeLogTest {
    private fun report(items: List<L7ProbeItem>) = L7ProbeReport(UUID.randomUUID().toString(), "generation", "test", 1,
        L7ProbePhase.COMPLETED, items, items.size, 2)

    @Test fun logsPreserveBatchEvidenceButNeverIncludePrivateFieldsOrSecretValues() {
        val report = report(listOf(L7ProbeItem("ENV-SYSTEM", "system", "ENVIRONMENT", L7ProbeOutcome.VERIFIED,
            "QUERY_ONLY", mapOf("api" to "30", "model" to "token=synthetic-secret", "serial" to "private-number", "rawName" to "private-name"))))
        val text = L7ProbeLog.lines(report).joinToString("\n")
        assertTrue(text.contains("batch=${L7ProbeLog.batch(report)}"))
        assertTrue(text.contains("entry=ENV-SYSTEM")); assertTrue(text.contains("api=30"))
        assertTrue(text.contains("event=end phase=COMPLETED"))
        assertFalse(text.contains("synthetic-secret")); assertFalse(text.contains("private-number")); assertFalse(text.contains("private-name"))
    }

    @Test fun twoCollectionsSurviveBufferClearAndBusyLogsForManualUploadWithoutSending() {
        val app = RuntimeEnvironment.getApplication()
        val uploadBefore = RemoteLogUpload.status
        val first = report(listOf(
            L7ProbeItem("ENV-SYSTEM", "system", "ENVIRONMENT", L7ProbeOutcome.VERIFIED, "QUERY_ONLY", mapOf("api" to "30")),
            L7ProbeItem("PERM:android.permission.RECORD_AUDIO", "RECORD_AUDIO", "PERMISSION", L7ProbeOutcome.DENIED,
                "NOT_GRANTED", mapOf("declared" to "true", "granted" to "false")),
        ))
        val second = first.copy(id = UUID.randomUUID().toString(), phase = L7ProbePhase.CANCELLED)
        L7ProbeLog.write(app, first); L7ProbeLog.write(app, second)
        L7DebugLog.buffer.clear()
        val retained = L7ProbeLog.read(app).joinToString("\n")
        assertTrue(retained.contains(L7ProbeLog.batch(first))); assertTrue(retained.contains(L7ProbeLog.batch(second)))
        repeat(1000) { L7DebugLog.buffer.append("Busy session event index=$it " + "detail ".repeat(60)) }
        val upload = RemoteLogReport.collect(app)
        val events = JSONArray(upload.body.toString(Charsets.UTF_8))
        assertTrue(events.toString().contains("phase=CANCELLED"))
        assertTrue(events.toString().contains(L7ProbeLog.batch(first)))
        assertTrue(events.toString().contains(L7ProbeLog.batch(second)))
        assertTrue(events.toString().contains("entry=ENV-SYSTEM status=SUPPORTED"))
        assertTrue(events.toString().contains("entry=PERM:android.permission.RECORD_AUDIO status=NO_PERMISSION"))
        assertTrue(events.toString().contains("declared=true granted=false"))
        for (i in 0 until events.length()) assertFalse(events.getJSONObject(i).has("device_id"))
        assertEquals(uploadBefore, RemoteLogUpload.status)
        L7DebugLog.buffer.clear()
    }

    @Test fun permissionNamesMatchingPayloadFiltersStillRetainEveryItemResult() {
        val report = report(listOf("CALL_PHONE", "FOREGROUND_SERVICE_MICROPHONE", "ACCESS_TOKEN").map {
            L7ProbeItem("PERM:android.permission.$it", it, "PERMISSION", L7ProbeOutcome.DENIED, "NOT_DECLARED")
        })
        val lines = L7ProbeLog.lines(report)
        report.items.indices.forEach { index ->
            assertTrue(lines.any { "item=$index entry=ENTRY_$index status=NO_PERMISSION" in it })
        }
        assertTrue(lines.all { RemoteLogReport.redact(it) == it })
    }

    @Test fun logBudgetUsesActualBytesAndRecordsTruncationAndTerminalState() {
        val huge = report((1..1000).map { L7ProbeItem("ENV-$it", "item", "ENVIRONMENT", L7ProbeOutcome.UNKNOWN,
            "QUERY_FAILED", mapOf("model" to "测试".repeat(600))) })
        val lines = L7ProbeLog.lines(huge)
        assertTrue(lines.joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8).size <= L7ProbeLog.MAX_BYTES)
        assertTrue(lines.any { "event=truncated" in it })
        assertTrue(lines.last().contains("event=end"))
        assertTrue(lines.all { it.length <= 700 })
    }
}
