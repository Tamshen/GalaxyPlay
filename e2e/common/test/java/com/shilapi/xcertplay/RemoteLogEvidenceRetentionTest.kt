package com.shilapi.xcertplay

import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class RemoteLogEvidenceRetentionTest {
    private fun line(id: String, millis: Long, text: String) = DiagnosticLogIdentity.prefix(id, millis) + text

    @After fun cleanup() {
        AsyncDiagnosticLog.awaitIdle(2000)
        ReflectionHelpers.setStaticField(L7DebugLog::class.java, "persistent", null)
        L7DebugLog.buffer.clear()
    }

    @Test fun sharedIdentityDeduplicatesCopiesButPreservesSeparateFailuresAndOldRecords() {
        val first = line(DiagnosticLogIdentity.next(), 1000, "ERROR timeout")
        val second = line(DiagnosticLogIdentity.next(), 1000, "ERROR timeout")
        val report = RemoteLogReport.create(listOf(first, first, second, "ERROR timeout", "ERROR timeout"), "test", "test")
        assertEquals(1, report.duplicateCopies)
        assertEquals(0, report.omittedLines)
        assertEquals(4, report.batches.flatMap { it.entries }.count { it.source == "runtime" })
    }

    @Test fun sameIdentityWithDifferentBodiesIsNotMerged() {
        val id = DiagnosticLogIdentity.next()
        val report = RemoteLogReport.create(listOf(line(id, 1, "ERROR first"), line(id, 1, "ERROR second")), "test", "test")
        assertEquals(0, report.duplicateCopies)
        assertEquals(2, report.batches.flatMap { it.entries }.count { it.source == "runtime" })
    }

    @Test fun largeMediaVolumeCannotPushOutConnectionStartFailureAndRelease() {
        val anchors = listOf("CORE_TRACE session=1 kind=START state=REQUESTED", "ERROR No AirPlay TCP after StartSession",
            "CORE_TRACE session=1 kind=RELEASE state=ENDED")
        val report = RemoteLogReport.create(anchors.asSequence() + (1..20_000).asSequence().map {
            "Video stats event=$it detail=" + "统计".repeat(300)
        }, "test", "test") {}
        val messages = report.batches.flatMap { it.entries }.map { it.message }
        assertTrue(messages.containsAll(anchors))
        assertTrue(report.omittedLines > 0)
        assertTrue(report.batches.sumOf { it.body.size } <= RemoteLogReport.MAX_REPORT_BODY)
    }

    @Test fun timestampsOrderRecordsAcrossSourcesAndDoNotMergeEqualMillisecondEvents() {
        val snapshot = RemoteLogSnapshot()
        val id = DiagnosticLogIdentity.next()
        snapshot.append("debug.log", line(id, 3000, "third"))
        snapshot.append("memory", line(DiagnosticLogIdentity.next(), 1000, "first"))
        snapshot.append("diplay.log", line(DiagnosticLogIdentity.next(), 2000, "second"))
        snapshot.append("memory", line(id, 3000, "third"))
        assertEquals(listOf("first", "second", "third"), snapshot.lines.map { it.text.substringAfterLast(' ') })
        assertEquals(1, snapshot.duplicateCopies)
    }

    @Test fun persistentSessionDebugAndMemoryCopiesShareIdentityAndSurviveSessionClose() {
        val app = RuntimeEnvironment.getApplication()
        L7DebugLog.initialize(app)
        val session = SessionLogFile(File(app.filesDir, "logs/diplay.log"))
        L7DebugLog.recordSession("CONNECTION_DIAGNOSTIC stage=Failed evidence=synthetic", session)
        assertTrue(AsyncDiagnosticLog.awaitIdle(2000))
        session.close()
        L7DebugLog.recordSession("CONNECTION_DIAGNOSTIC teardown end evidence=synthetic-release", session)
        assertTrue(AsyncDiagnosticLog.awaitIdle(2000))
        val report = RemoteLogReport.collect(app)
        val entries = report.batches.flatMap { it.entries }
        assertEquals(1, entries.count { it.message.contains("evidence=synthetic ") || it.message.endsWith("evidence=synthetic") })
        assertEquals(1, entries.count { it.message.contains("evidence=synthetic-release") })
        assertTrue(report.duplicateCopies >= 3)
    }
}
