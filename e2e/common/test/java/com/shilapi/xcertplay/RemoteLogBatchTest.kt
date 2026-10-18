package com.shilapi.xcertplay

import android.content.Context
import android.content.ContextWrapper
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
    private var serverWorkers: ExecutorService? = null
    private val folder get() = File(app.filesDir, "logs")
    @Before fun before() { RemoteLogUpload.cancel(); folder.deleteRecursively(); folder.mkdirs(); L7DebugLog.buffer.clear() }
    @After fun after() { RemoteLogUpload.cancel(); server?.stop(0); serverWorkers?.shutdownNow() }

    @Test fun repeatedCancellationDoesNotCreateParallelCollectorsOrSendStaleSnapshots() {
        val received = CopyOnWriteArrayList<String>()
        configure { text, _ -> received += text; 200 }
        File(folder, "diplay.log").writeText("synthetic latest upload\n")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File {
                if (calls.getAndIncrement() == 0) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                return app.filesDir
            }
        }
        try {
            assertTrue(RemoteLogUpload.start(context))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            repeat(20) {
                RemoteLogUpload.cancel()
                assertTrue(RemoteLogUpload.start(context))
            }
            assertEquals(1, calls.get())
            assertTrue(received.isEmpty())
        } finally { release.countDown() }
        await(RemoteLogUpload.Phase.SUCCESS)
        assertEquals(1, received.size)
        assertTrue(received.single().contains("synthetic latest upload"))
    }

    @Test fun fullRotatedHistoryKeepsRecentEvidenceAndBoundedJson() {
        val configuration = GalaxyConfigurationEvidence.capture(app)
        val text = configuration.header + "\n" + (0..1200).joinToString("\n") {
            "config_ref=${configuration.id} event=$it codec=HEVC token=private-secret detail=" + "counter ".repeat(25)
        }
        assertTrue(text.toByteArray().size <= SessionLogFile.MAX_BYTES)
        for (name in SessionLogFile.REPORT_NAMES + L7ProbeLog.files) File(folder, name).writeText(text)
        val report = RemoteLogReport.collect(app)
        assertTrue(report.omittedLines > 0)
        assertTrue(report.batches.sumOf { it.body.size } <= RemoteLogReport.MAX_REPORT_BODY)
        val records = report.batches.flatMap { it.entries }
        assertTrue(records.any { it.source == "probe-latest.log" && "event=1200 " in it.message })
        assertTrue(records.any { it.configurationRole == "at_event" && it.configurationId == configuration.id })
        assertFalse(records.any { "private-secret" in it.message })
    }

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

    @Test fun retryAfterPreparationFailureKeepsTheRequestHashAndRemark() {
        val received = CopyOnWriteArrayList<String>()
        configure { text, _ -> received += text; 200 }
        File(folder, "diplay.log").writeText("synthetic preparation retry\n")
        val calls = AtomicInteger()
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File {
                if (calls.getAndIncrement() == 0) throw IllegalStateException("synthetic preparation failure")
                return app.filesDir
            }
        }
        assertTrue(RemoteLogUpload.start(context, remark = "整理失败后重试"))
        await(RemoteLogUpload.Phase.FAILED)
        val id = RemoteLogUpload.status.id
        assertTrue(RemoteLogUpload.status.preparationFailed)
        assertTrue(received.isEmpty())
        assertTrue(RemoteLogUpload.start(context, retry = true, remark = "不能覆盖"))
        await(RemoteLogUpload.Phase.SUCCESS)
        assertEquals(id, RemoteLogUpload.status.id)
        assertEquals("整理失败后重试", RemoteLogUpload.status.remark)
        assertEquals(id, JSONArray(received.single()).getJSONObject(0).getString("report_id"))
    }

    @Test fun failedBatchStopsAndManualRetrySkipsConfirmedBatchesWithStableEvents() {
        val configuration = GalaxyConfigurationEvidence.capture(app)
        File(folder, "diplay.log").writeText(configuration.header + "\n" + (0..2400).joinToString("\n") {
            "config_ref=${configuration.id} event=$it HEVC codec=c2.qti.hevc.decoder " + "frame=1920x1440 ".repeat(5)
        })
        val received = CopyOnWriteArrayList<Pair<Int, String>>()
        configure { text, data ->
            val batch = data.getJSONObject(0).getInt("batch_index")
            received += batch to text
            if (batch == 2 && received.count { it.first == 2 } == 1) 503 else 200
        }
        assertTrue(RemoteLogUpload.start(app, remark = "18点更新后连接失败"))
        await(RemoteLogUpload.Phase.FAILED)
        assertNull(RemoteLogHistory.last(app))
        val firstAttempt = received.toList()
        assertTrue(firstAttempt.any { it.first == 2 })
        assertEquals(firstAttempt.size - 1, RemoteLogUpload.status.completedBatches)
        val reportId = RemoteLogUpload.status.id
        Thread.sleep(100)
        assertEquals(firstAttempt.size, received.size)
        // 失败后切换车型与参数，重试仍发送原报告，而不是读取新配置冒充旧测试。
        val repository = GalaxyProfiles(app)
        val draft = GalaxyConfigurationContext(app, repository.draft("current", "Retry next configuration"), true)
        L7AudioTemplates.selectModel(draft, L7AudioTemplates.Model.L6)
        val saved = repository.save(draft.profile.copy(configuration = draft.configuration()))
        repository.select(saved.id)
        assertTrue(RemoteLogUpload.start(app, retry = true, remark = "不能覆盖原备注"))
        await(RemoteLogUpload.Phase.SUCCESS)
        assertEquals(firstAttempt.single { it.first == 2 }.second, received.last { it.first == 2 }.second)
        val status = RemoteLogUpload.status
        assertEquals(reportId, status.id)
        assertEquals("18点更新后连接失败", status.remark)
        assertTrue(received.all { (_, body) ->
            val rows = JSONArray(body)
            (0 until rows.length()).all { rows.getJSONObject(it).getString("upload_remark") == status.remark }
        })
        assertEquals(status.totalBatches, status.completedBatches)
        assertEquals(status.totalLines, status.uploadedLines)
        assertEquals(RemoteLogHistory.Entry(status.finishedAt, status.totalLines, status.id, status.remark, status.stream),
            RemoteLogHistory.last(app))
        assertTrue(status.finishedAt > 0)
        val alreadyConfirmed = firstAttempt.map { it.first }.filter { it != 2 }.toSet()
        assertEquals((1..status.totalBatches).filter { it !in alreadyConfirmed }.toSet(),
            received.drop(firstAttempt.size).map { it.first }.toSet())
        assertEquals(status.totalBatches + 1, received.size)
    }

    @Test fun fullUploadAndRetryDeliverEveryLocalLineBeyondTheOldSnapshotBudget() {
        File(folder, "previous-7.log").writeText((0 until 6200).joinToString("\n") {
            "full_upload_index=$it codec=H264 " + "counter ".repeat(12)
        })
        val received = CopyOnWriteArrayList<Pair<Int, String>>()
        val accepted = CopyOnWriteArrayList<Int>()
        configure { text, data ->
            val batch = data.getJSONObject(0).getInt("batch_index")
            received += batch to text
            if (batch == 3 && received.count { it.first == 3 } == 1) 503 else {
                for (i in 0 until data.length()) {
                    Regex("full_upload_index=(\\d+)").find(data.getJSONObject(i).getString("message"))
                        ?.let { accepted += it.groupValues[1].toInt() }
                }
                200
            }
        }
        assertTrue(RemoteLogUpload.start(app))
        await(RemoteLogUpload.Phase.FAILED, 30)
        val firstAttempt = received.toList()
        assertEquals(firstAttempt.size - 1, RemoteLogUpload.status.completedBatches)
        assertEquals(0, RemoteLogUpload.status.omittedLines)
        File(folder, "previous-7.log").writeText("different newer session\n")
        assertTrue(RemoteLogUpload.start(app, retry = true))
        await(RemoteLogUpload.Phase.SUCCESS, 30)
        assertEquals(firstAttempt.single { it.first == 3 }.second, received.last { it.first == 3 }.second)
        assertEquals((0 until 6200).toList(), accepted.sorted())
        assertEquals(RemoteLogUpload.status.totalBatches + 1, received.size)
        assertTrue(received.sumOf { it.second.toByteArray().size } > 1024 * 1024)
        assertEquals(RemoteLogUpload.status.totalLines, RemoteLogUpload.status.uploadedLines)
        assertTrue(File(app.cacheDir, "log-upload-snapshots").listFiles().orEmpty().isEmpty())
    }

    @Test fun cancelPreventsLaterBatchesAndLateSuccessCannotRestoreTheTask() {
        File(folder, "diplay.log").writeText((0..2200).joinToString("\n") { "event=$it HEVC " + "detail ".repeat(15) })
        val entered = CountDownLatch(3)
        val finish = CountDownLatch(1)
        val received = CopyOnWriteArrayList<Int>()
        configure { _, data -> received += data.length(); entered.countDown(); finish.await(3, TimeUnit.SECONDS); 200 }
        assertTrue(RemoteLogUpload.start(app))
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        RemoteLogUpload.cancel()
        finish.countDown()
        Thread.sleep(200)
        assertEquals(RemoteLogUpload.Phase.CANCELLED, RemoteLogUpload.status.phase)
        assertEquals(3, received.size)
    }

    private fun records(report: RemoteLogReport) = report.batches.flatMap { batch ->
        val data = JSONArray(batch.body.toString(Charsets.UTF_8))
        (0 until data.length()).map(data::getJSONObject)
    }

    private fun configure(reply: (String, JSONArray) -> Int) {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.executor = Executors.newFixedThreadPool(3).also { serverWorkers = it }
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

    private fun await(phase: RemoteLogUpload.Phase, seconds: Long = 10) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (RemoteLogUpload.status.phase == RemoteLogUpload.Phase.UPLOADING && System.nanoTime() < deadline) Thread.sleep(20)
        assertEquals(phase, RemoteLogUpload.status.phase)
    }
}
