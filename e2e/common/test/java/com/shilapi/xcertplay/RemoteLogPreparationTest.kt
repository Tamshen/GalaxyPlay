package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RemoteLogPreparationTest {
    @Test fun historicalVolumeDoesNotIncreaseRetainedPreparationWork() {
        val snapshot = RemoteLogSnapshot()
        repeat(100_000) { snapshot.append("runtime", "event=$it detail=" + "计数".repeat(400)) }
        assertEquals(100_000, snapshot.count)
        assertTrue(snapshot.lines.size < RemoteLogReport.MAX_LINES)
        assertTrue(snapshot.lines.sumOf { it.text.length } <= RemoteLogSnapshot.MAX_CHARACTERS)
        assertEquals(100_000, snapshot.lines.size + snapshot.omitted)
        assertEquals(99_999, snapshot.lines.last().index)
    }

    @Test fun smallLinesRemainLimitedByRecordCountAndAreNotDeduplicated() {
        val snapshot = RemoteLogSnapshot()
        repeat(20_000) { snapshot.append("runtime", "audio underrun count=1") }
        assertEquals(RemoteLogReport.MAX_LINES, snapshot.lines.size)
        assertEquals(15_000, snapshot.omitted)
        assertEquals(15_000, snapshot.lines.first().index)
    }

    @Test fun repeatedConfigurationHeadersHaveOneBoundedCandidate() {
        val snapshot = RemoteLogSnapshot()
        repeat(20_000) { snapshot.append("runtime", GalaxyConfigurationEvidence.PREFIX + "same header") }
        assertEquals(listOf("same header"), snapshot.headers.toList())
        repeat(200) { snapshot.append("runtime", GalaxyConfigurationEvidence.PREFIX + "header $it") }
        assertEquals(RemoteLogSnapshot.MAX_HEADERS, snapshot.headers.size)
        snapshot.append("runtime", GalaxyConfigurationEvidence.PREFIX + "x".repeat(100_000))
        assertEquals(RemoteLogSnapshot.MAX_HEADERS, snapshot.headers.size)
        assertTrue(snapshot.lines.isEmpty())
    }

    @Test fun oversizedOrdinaryLineIsBoundedBeforeRedactionAndStillMasksSecrets() {
        val snapshot = RemoteLogSnapshot()
        snapshot.append("runtime", "token=private-secret detail=" + "x".repeat(500_000))
        assertTrue(snapshot.lines.single().text.length <= DiagnosticRedactor.MAX_LINE + 12)
        val report = RemoteLogReport.create(snapshot.lines.map { it.text }, "test", "test")
        val message = report.batches.flatMap { it.entries }.first().message
        assertFalse(message.contains("private-secret"))
        assertTrue(message.contains("oversized_log_line omittedChars="))
        assertTrue(message.endsWith(" [truncated]"))
        assertTrue(report.batches.sumOf { it.body.size } <= RemoteLogReport.MAX_REPORT_BODY)
    }

    @Test fun oversizedRawPayloadCannotBecomeUploadableAfterClipping() {
        for (raw in listOf("a".repeat(500_000), "header " + "a".repeat(500_000) + " -----BEGIN PRIVATE KEY-----")) {
            val report = RemoteLogReport.create(listOf(raw), "test", "test")
            val body = report.batches.single().body.toString(Charsets.UTF_8)
            assertFalse(body.contains("a".repeat(100)))
            assertFalse(body.contains("PRIVATE KEY"))
            assertTrue(body.contains("oversized_log_line"))
        }
    }

    @Test fun cancellationStopsReadingTheRemainingHistory() {
        var produced = 0
        val stopped = IllegalStateException("cancelled")
        val lines = sequence { repeat(100_000) { produced++; yield("event=$it") } }
        val result = runCatching { RemoteLogReport.create(lines, "test", "test") {
            if (produced == 50) throw stopped
        } }
        assertSame(stopped, result.exceptionOrNull())
        assertEquals(50, produced)
    }

    @Test fun cancellationAlsoStopsFinalPreparationAfterScanning() {
        var checks = 0
        val stopped = IllegalStateException("cancelled")
        val result = runCatching { RemoteLogReport.create(sequenceOf("event=1", "event=2"), "test", "test") {
            if (++checks == 3) throw stopped
        } }
        assertSame(stopped, result.exceptionOrNull())
        assertEquals(3, checks)
    }
}
