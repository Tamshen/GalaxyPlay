package com.shilapi.xcertplay

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RemoteLogBatchTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private var server: HttpServer? = null
    private val folder get() = File(app.filesDir, "logs")
    @Before fun before() { RemoteLogUpload.cancel(); folder.deleteRecursively(); folder.mkdirs(); L7DebugLog.buffer.clear() }
    @After fun after() { RemoteLogUpload.cancel(); server?.stop(0) }

    @Test fun totalJsonIsOneMiBAndBatchesKeepLatestCountersAndExplicitOmissions() {
        val report = RemoteLogReport.create((0..4000).map { "event=$it codec=HEVC " + "解码\"".repeat(200) }, "test", "test")
        assertTrue(report.batches.size > 1)
        assertTrue(report.batches.sumOf { it.body.size } <= 1024 * 1024)
        assertTrue(report.batches.all { it.body.size <= 256 * 1024 && it.lineCount <= 1000 })
        assertTrue(report.omittedLines > 0)
        val records = records(report)
        assertTrue(records.last().getString("message").contains("omittedLines=${report.omittedLines}"))
        assertTrue(records.any { "event=4000 " in it.getString("message") })
        assertEquals(records.size, records.map { it.getString("event_id") }.distinct().size)
        assertTrue(records.none { it.has("device_id") })
        assertTrue(records.all { it.getInt("batch_count") == report.batches.size })
        val repeats = RemoteLogReport.create(List(20) { "audio underrun count=1" }, "test", "test")
        assertEquals(20, records(repeats).count { it.getString("message") == "audio underrun count=1" })
    }

    @Test fun collectionIncludesAllEightRetainedFilesAndBothEnvironmentBatches() {
        for (name in SessionLogFile.REPORT_NAMES + L7ProbeLog.files) File(folder, name).writeText("sample sourceKind=$name codec=HEVC\n")
        val records = records(RemoteLogReport.collect(app))
        for (name in SessionLogFile.REPORT_NAMES + L7ProbeLog.files)
            assertTrue(name, records.any { it.getString("source") == name && "codec=HEVC" in it.getString("message") })
    }

    @Test fun emptyCollectionDoesNotSendSummariesOrOverwriteSuccessfulHistory() {
        val received = CopyOnWriteArrayList<String>()
        configure { text, _ -> received += text; 200 }
        val history = RemoteLogHistory.Entry(123456, 12)
        RemoteLogHistory.save(app, history)
        assertTrue(RemoteLogReport.create(listOf("", " "), "test", "test").batches.isEmpty())
        assertTrue(RemoteLogReport.collect(app).batches.isEmpty())
        assertTrue(RemoteLogUpload.start(app))
        await(RemoteLogUpload.Phase.EMPTY)
        assertTrue(received.isEmpty())
        assertEquals(0, RemoteLogUpload.status.totalLines)
        assertEquals(history, RemoteLogHistory.last(app))
    }

    @Test fun clearingLogsOrReportsDiscardsFailedRetryAndOnlyUploadsAFreshSnapshot() {
        val received = CopyOnWriteArrayList<String>()
        configure { text, _ -> received += text; 400 }
        for (logs in listOf(false, true)) {
            L7DebugLog.buffer.append("synthetic before clear")
            assertTrue(RemoteLogUpload.start(app))
            await(RemoteLogUpload.Phase.FAILED)
            val oldId = RemoteLogUpload.status.id
            assertTrue(L7ProbeRunner.clear(app, logs) {})
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (L7ProbeRunner.busy && System.nanoTime() < deadline) Thread.sleep(10)
            assertFalse(L7ProbeRunner.busy)
            assertEquals(RemoteLogUpload.Phase.CANCELLED, RemoteLogUpload.status.phase)
            L7DebugLog.buffer.append("synthetic after clear")
            assertTrue(RemoteLogUpload.start(app, retry = true))
            await(RemoteLogUpload.Phase.FAILED)
            assertNotEquals(oldId, RemoteLogUpload.status.id)
            if (logs) assertFalse(received.last().contains("synthetic before clear"))
            assertTrue(received.last().contains("synthetic after clear"))
        }
    }

    @Test fun failedBatchStopsAndManualRetrySkipsConfirmedBatchesWithStableEvents() {
        File(folder, "diplay.log").writeText((0..2400).joinToString("\n") { "event=$it HEVC codec=c2.qti.hevc.decoder " + "frame=1920x1440 ".repeat(5) })
        val received = CopyOnWriteArrayList<Pair<Int, String>>()
        configure { text, data ->
            val batch = data.getJSONObject(0).getInt("batch_index")
            received += batch to text
            if (received.size == 2) 503 else 200
        }
        assertTrue(RemoteLogUpload.start(app))
        await(RemoteLogUpload.Phase.FAILED)
        assertNull(RemoteLogHistory.last(app))
        assertEquals(listOf(1, 2), received.map { it.first })
        assertEquals(1, RemoteLogUpload.status.completedBatches)
        Thread.sleep(100)
        assertEquals(2, received.size)
        assertTrue(RemoteLogUpload.start(app, retry = true))
        await(RemoteLogUpload.Phase.SUCCESS)
        assertEquals(listOf(1, 2, 2), received.take(3).map { it.first })
        assertEquals(received[1].second, received[2].second)
        val status = RemoteLogUpload.status
        assertEquals(status.totalBatches, status.completedBatches)
        assertEquals(status.totalLines, status.uploadedLines)
        assertEquals(RemoteLogHistory.Entry(status.finishedAt, status.totalLines), RemoteLogHistory.last(app))
        assertTrue(status.finishedAt > 0)
        assertEquals((3..status.totalBatches).toList(), received.drop(3).map { it.first })
    }

    @Test fun cancelPreventsLaterBatchesAndLateSuccessCannotRestoreTheTask() {
        File(folder, "diplay.log").writeText((0..2200).joinToString("\n") { "event=$it HEVC " + "detail ".repeat(15) })
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val received = CopyOnWriteArrayList<Int>()
        configure { _, data -> received += data.length(); entered.countDown(); finish.await(3, TimeUnit.SECONDS); 200 }
        assertTrue(RemoteLogUpload.start(app))
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        RemoteLogUpload.cancel()
        finish.countDown()
        Thread.sleep(200)
        assertEquals(RemoteLogUpload.Phase.CANCELLED, RemoteLogUpload.status.phase)
        assertEquals(1, received.size)
    }

    private fun records(report: RemoteLogReport) = report.batches.flatMap { batch ->
        val data = JSONArray(batch.body.toString(Charsets.UTF_8))
        (0 until data.length()).map(data::getJSONObject)
    }

    private fun configure(reply: (String, JSONArray) -> Int) {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/") { exchange ->
            val text = exchange.requestBody.bufferedReader().readText()
            val data = JSONArray(text)
            val code = reply(text, data)
            val body = JSONObject().put("code", 200).put("status", JSONArray().put(JSONObject()
                .put("name", RemoteLogDevice.id(app)).put("successful", data.length()).put("failed", 0))).toString().toByteArray()
            runCatching { exchange.sendResponseHeaders(code, body.size.toLong()); exchange.responseBody.use { it.write(body) } }
        }
        http.start(); server = http
        RemoteLogConfig.save(app, RemoteLogConfig("http://127.0.0.1:${http.address.port}/api/default/123/_json", "Basic dGVzdDpwYXNz"))
        L7Agreement.accept(app)
    }

    private fun await(phase: RemoteLogUpload.Phase) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (RemoteLogUpload.status.phase == RemoteLogUpload.Phase.UPLOADING && System.nanoTime() < deadline) Thread.sleep(20)
        assertEquals(phase, RemoteLogUpload.status.phase)
    }
}
