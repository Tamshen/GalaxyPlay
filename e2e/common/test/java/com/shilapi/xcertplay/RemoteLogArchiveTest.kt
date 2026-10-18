package com.shilapi.xcertplay

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30])
class RemoteLogArchiveTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val logs get() = File(app.filesDir, "logs")
    private val snapshots get() = File(app.cacheDir, "log-upload-snapshots")
    @Before fun setup() { RemoteLogUpload.cancel(); logs.deleteRecursively(); logs.mkdirs(); L7DebugLog.buffer.clear() }
    @After fun cleanup() { RemoteLogUpload.cancel(); snapshots.deleteRecursively() }

    @Test fun markedUploadHasOnePairOfBoundariesAndSearchableIdentityOnEveryRecord() {
        File(logs, "diplay.log").writeText((0..1200).joinToString("\n") { "sample_event=$it" })
        val request = RemoteLogUploadRequest.create("18点 更新后失败\n再试一次")
        RemoteLogArchive.collect(app, request = request).use { report ->
            assertEquals(request.id, report.id)
            val rows = report.batches.flatMap { batch ->
                val json = org.json.JSONArray(batch.body.toString(Charsets.UTF_8))
                (0 until json.length()).map(json::getJSONObject)
            }
            assertTrue(rows.first().getString("message").startsWith("UPLOAD_BEGIN "))
            assertTrue(rows.last().getString("message").startsWith("UPLOAD_END "))
            assertEquals(2, rows.count { it.getString("source") == "upload" })
            assertTrue(rows.all { it.getString("report_id") == request.id &&
                it.getString("upload_remark") == "18点 更新后失败 再试一次" })
            assertTrue(rows.first().getInt("line_index") < rows.drop(1).minOf { it.getInt("line_index") })
            assertEquals(rows.size, rows.map { it.getString("event_id") }.distinct().size)
            assertEquals(1201, rows.count { it.getString("source") == "diplay.log" })
            assertTrue(rows.first().getString("message").contains(request.id))
            assertTrue(rows.last().getString("message").contains(request.remark))
            assertTrue(rows.last().getString("message").endsWith("records=${rows.size}"))
        }
    }

    @Test fun fullHistoryBeyondFiveThousandLinesAndOneMiBKeepsBeginningMiddleAndEnd() {
        val text = (0 until 6500).joinToString("\n") { "evidence_index=$it codec=H264 " + "counter ".repeat(15) }
        File(logs, "previous-7.log").writeText(text)
        File(logs, "diplay.log").writeText("ERROR final connection failed token=private-secret\n")
        RemoteLogArchive.collect(app).use { report ->
            assertEquals(0, report.omittedLines)
            assertEquals(0, report.shortenedSources)
            assertTrue(report.lineCount > 6500)
            assertTrue(report.batches.size > 4)
            assertTrue(report.batches.sumOf { it.body.size } > 1024 * 1024)
            val actual = mutableListOf<Int>()
            report.batches.forEach { batch ->
                assertTrue(batch.body.size <= RemoteLogReport.MAX_BODY)
                assertTrue(batch.lineCount <= RemoteLogReport.MAX_BATCH_LINES)
                batch.entries.filter { it.source == "previous-7.log" }.forEach {
                    actual += Regex("evidence_index=(\\d+)").find(it.message)!!.groupValues[1].toInt()
                }
                assertFalse(batch.body.toString(Charsets.UTF_8).contains("private-secret"))
            }
            assertEquals((0 until 6500).toList(), actual)
        }
        assertTrue(snapshots.listFiles().orEmpty().isEmpty())
    }

    @Test fun frozenBatchesDoNotChangeWhenSourceRotatesAndDuplicateEventsAreMerged() {
        val id = DiagnosticLogIdentity.next()
        val first = DiagnosticLogIdentity.prefix(id, 2000) + "ERROR repeated failure"
        val earlier = DiagnosticLogIdentity.prefix(DiagnosticLogIdentity.next(), 1000) + "connection begin"
        File(logs, "diplay.log").writeText(first + "\n" + earlier + "\n")
        L7DebugLog.buffer.append(first)
        RemoteLogArchive.collect(app).use { report ->
            assertEquals(1, report.duplicateCopies)
            val frozen = report.batches.map { it.body.toList() }
            File(logs, "diplay.log").writeText("new session\n")
            assertEquals(frozen, report.batches.map { it.body.toList() })
            val lines = report.batches.flatMap { it.entries }.filter { it.source == "diplay.log" }
            assertTrue(lines.first().message.endsWith("connection begin"))
            assertEquals(1, lines.count { it.message.endsWith("ERROR repeated failure") })
        }
    }

    @Test fun completeFileReadIncludesDataPastOldPerSourceLimitAndMalformedLargeLineIsBounded() {
        File(logs, "debug.log").writeText("start evidence\n" + "x".repeat(600_000) + "\nend evidence\n")
        RemoteLogArchive.collect(app).use { report ->
            assertEquals(0, report.shortenedSources)
            val lines = report.batches.flatMap { it.entries }.filter { it.source == "debug.log" }
            assertEquals(3, lines.size)
            assertEquals("start evidence", lines.first().message)
            assertEquals("end evidence", lines.last().message)
            assertTrue(lines[1].message.contains("omittedChars=600000"))
        }
    }

    @Test fun cancelledPreparationRemovesAllTemporaryInputsAndIndex() {
        File(logs, "diplay.log").writeText((0..2000).joinToString("\n") { "counter=$it" })
        val calls = AtomicInteger()
        val stopped = IllegalStateException("cancelled")
        val result = runCatching { RemoteLogArchive.collect(app) { if (calls.incrementAndGet() == 40) throw stopped } }
        assertSame(stopped, result.exceptionOrNull())
        assertTrue(snapshots.listFiles().orEmpty().isEmpty())
    }

    @Test fun historicalConfigurationsBeyondOldHeaderLimitStayAttachedToEveryEvent() {
        val profile = GalaxyProfiles(app).refresh()
        val evidence = (1..70).map { GalaxyConfigurationEvidence.from(app, profile.copy(revision = it)) }
        File(logs, "previous-7.log").writeText(evidence.mapIndexed { index, config ->
            config.header + "\nconfig_ref=${config.id} history_config_event=$index"
        }.joinToString("\n"))
        RemoteLogArchive.collect(app).use { report ->
            val rows = report.batches.flatMap { it.entries }
            assertEquals(evidence.map { it.id }.toSet(), rows.filter { it.configurationRole == "at_event" }
                .map { it.configurationId }.toSet())
            assertEquals(70, rows.count { "history_config_event=" in it.message })
        }
    }

    @Test fun sameEventWithDifferentRawTextIsRetainedAndNullBindingsCannotReuseThePreviousConfiguration() {
        val evidence = GalaxyConfigurationEvidence.capture(app)
        val event = DiagnosticLogIdentity.prefix(DiagnosticLogIdentity.next(), 1000)
        val first = "config_ref=${evidence.id} " + event + "failure token=first-secret"
        val different = "config_ref=${evidence.id} " + event + "failure token=other-secret"
        File(logs, "diplay.log").writeText(evidence.header + "\n$first\n$different\n$first\nplain event\nplain event\n")
        val progress = mutableListOf<Int>()
        RemoteLogArchive.collect(app, progress = progress::add).use { report ->
            assertEquals(1, report.duplicateCopies)
            val rows = report.batches.flatMap { it.entries }.filter { it.source == "diplay.log" }
            assertEquals(4, rows.size)
            assertEquals(2, rows.count { it.configurationId == evidence.id })
            assertEquals(2, rows.count { it.configurationId == null && it.message == "plain event" })
            assertTrue(progress.last() >= 6)
            val body = report.batches.joinToString { it.body.toString(Charsets.UTF_8) }
            assertFalse(body.contains("first-secret"))
            assertFalse(body.contains("other-secret"))
            assertTrue(body.contains("prepareIndexMs="))
        }
    }

    @Test fun frozenConfigurationIsStillSanitizedAtSendAndBatchFieldsInsideMessagesRemainUntouched() {
        val evidence = GalaxyConfigurationEvidence.capture(app)
        val message = "quoted {\"batch_index\":2147483647,\"batch_count\":2147483647} unicode=中文"
        File(logs, "diplay.log").writeText(evidence.header + "\nconfig_ref=${evidence.id} $message\n")
        RemoteLogArchive.collect(app).use { report ->
            val data = snapshots.listFiles()!!.single().resolve("batches.bin")
            data.writeText(data.readText().replace("[redacted]", "secret1234"))
            val rows = org.json.JSONArray(report.batches.first().body.toString(Charsets.UTF_8))
            for (index in 0 until rows.length()) {
                val row = rows.getJSONObject(index)
                assertEquals(1, row.getInt("batch_index"))
                assertEquals(report.batches.size, row.getInt("batch_count"))
                row.optJSONObject("configuration_file")?.let { assertFalse(it.toString().contains("secret1234")) }
                if (row.getString("source") == "diplay.log") assertTrue(row.getString("message").endsWith(message))
            }
        }
    }
}
