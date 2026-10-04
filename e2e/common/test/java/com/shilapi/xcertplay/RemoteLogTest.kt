package com.shilapi.xcertplay

import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R
import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.json.JSONArray
import java.util.Base64
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RemoteLogTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val token = "Basic " + Base64.getEncoder().encodeToString(("test:" + UUID.randomUUID()).toByteArray())
    private var server: HttpServer? = null

    @Before fun before() {
        RemoteLogUpload.cancel()
        context.getSharedPreferences("l7_remote_log", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("l7_agreement", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun after() { RemoteLogUpload.cancel(); server?.stop(0) }

    @Test fun configurationRejectsEmbeddedCredentialsAndHeaderInjection() {
        listOf("https://logs.example/api/default/l7carplay/_json", "http://127.0.0.1:8080/api/default/l7carplay/_json").forEach {
            assertTrue(RemoteLogConfig(it, token).valid())
        }
        listOf("", "ftp://logs.example/api/default/l7carplay/_json", "https://u:p@logs.example/api/default/l7carplay/_json", "https://logs.example/api/default/l7carplay/_json?t=x",
            "https://logs.example/api/default/l7carplay/_json#x", "https://logs.example:99999/api/default/l7carplay/_json", "https://logs.example/\n").forEach {
            assertFalse(RemoteLogConfig(it, token).valid())
        }
        assertFalse(RemoteLogConfig("https://logs.example/api/default/l7carplay/_json", "x\r\nHeader: y").valid())
    }

    @Test fun applicationOverrideCanBeClearedWithoutChangingBuildDefaults() {
        val defaults = RemoteLogConfig.load(context)
        val changed = RemoteLogConfig("https://logs.example/api/default/l7carplay/_json", token)
        assertTrue(RemoteLogConfig.save(context, changed))
        assertEquals(changed, RemoteLogConfig.load(context))
        assertTrue(RemoteLogConfig.reset(context))
        assertEquals(defaults, RemoteLogConfig.load(context))
    }

    @Test fun reportRedactsSecretsAndLimitsActualUtf8JsonBytes() {
        val report = RemoteLogReport.create(listOf("token=do-not-send", "peerName=private-phone",
            "serial=private-car", "payload=private-voice", "file=/data/user/0/private", "Audio source=192.168.1.4") +
            (1..2000).map { "$it " + "解码\"".repeat(200) }, "0.test", "DiPlay test")
        assertTrue(report.batches.all { it.body.size <= RemoteLogReport.MAX_BODY })
        assertTrue(report.batches.sumOf { it.body.size } <= RemoteLogReport.MAX_REPORT_BODY)
        assertTrue(report.lineCount > 1)
        val text = records(report).toString()
        assertFalse(text.contains("private"))
        assertFalse(text.contains("192.168.1.4"))
        assertTrue(text.contains("2000"))
        assertEquals(report.id, JSONArray(text).getJSONObject(0).getString("report_id"))
        val records = JSONArray(text)
        for (index in 0 until records.length()) assertFalse(records.getJSONObject(index).has("device_id"))
        assertEquals("serial=[redacted]", RemoteLogReport.redact("serial=private-car"))
        assertEquals("Audio source=[ip]", RemoteLogReport.redact("Audio source=192.168.1.4"))
    }

    @Test fun redirectCannotForwardUploadCredentialsToAnotherEndpoint() {
        val requests = AtomicInteger()
        val endpoint = serve { exchange ->
            requests.incrementAndGet()
            exchange.responseHeaders.add("Location", "/other")
            exchange.sendResponseHeaders(302, -1); exchange.close()
        }
        val report = RemoteLogReport.create(listOf("Audio ready"), "test", "test")
        RemoteLogTransport().use { assertEquals(302, it.send(RemoteLogConfig(endpoint, token), report.batches.first())) }
        assertEquals(1, requests.get())
    }

    @Test fun successfulHttpWithWrongStreamIsNotAnAcknowledgement() {
        val endpoint = serve { exchange ->
            val response = acknowledgement("wrong-stream", 1, 0)
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        val report = RemoteLogReport.create(listOf("Audio ready"), "test", "test")
        RemoteLogTransport().use {
            assertTrue(runCatching { it.send(RemoteLogConfig(endpoint, token), report.batches.first()) }.isFailure)
        }
    }

    @Test fun quickActionsOnlyUploadAfterClickAndConsentAndManualRetryReusesReport() {
        val ids = CopyOnWriteArrayList<String>()
        val endpoint = serve { exchange ->
            assertEquals(token, exchange.requestHeaders.getFirst("Authorization"))
            val payload = JSONArray(exchange.requestBody.bufferedReader().readText())
            ids += payload.getJSONObject(0).getString("report_id")
            assertEquals("/api/default/${RemoteLogDevice.id(context)}/_json", exchange.requestURI.path)
            assertFalse(payload.getJSONObject(0).has("device_id"))
            val response = acknowledgement(RemoteLogDevice.id(context), payload.length(), 0)
            exchange.sendResponseHeaders(if (ids.size == 1) 503 else 201, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        RemoteLogConfig.save(context, RemoteLogConfig(endpoint, token))
        val content = LinearLayout(context)
        var views = 0
        val actions = L7QuickLogActions(context, content, { views++ }, {})
        fun button(id: Int): Button {
            val matches = ArrayList<View>()
            content.findViewsWithText(matches, context.getString(id), View.FIND_VIEWS_WITH_TEXT)
            return matches.filterIsInstance<Button>().single()
        }
        actions.update()
        assertTrue(ids.isEmpty())
        button(R.string.l7_log_view_short).performClick()
        assertEquals(1, views)
        button(R.string.l7_log_upload).performClick()
        assertTrue(ids.isEmpty())
        assertTrue(L7Agreement.accept(context))
        actions.update()
        assertTrue(ids.isEmpty())
        button(R.string.l7_log_upload).performClick()
        awaitPhase(RemoteLogUpload.Phase.FAILED)
        Thread.sleep(150)
        assertEquals(1, ids.size)
        actions.update()
        val retry = button(R.string.l7_log_retry)
        assertEquals(View.VISIBLE, retry.visibility)
        retry.performClick()
        awaitPhase(RemoteLogUpload.Phase.SUCCESS)
        actions.update()
        assertEquals(View.GONE, retry.visibility)
        assertEquals(2, ids.size)
        assertEquals(ids[0], ids[1])
        assertTrue(L7RemoteLogSettings.statusText(context, RemoteLogUpload.status, RemoteLogDevice.id(context))
            .contains(RemoteLogDevice.id(context)))
    }

    @Test fun cancelBlocksRepeatedClicksAndLateCompletionCannotRestoreSuccess() {
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val endpoint = serve { exchange ->
            entered.countDown()
            finish.await(3, TimeUnit.SECONDS)
            runCatching { exchange.sendResponseHeaders(503, -1); exchange.close() }
        }
        RemoteLogConfig.save(context, RemoteLogConfig(endpoint, token))
        L7Agreement.accept(context)
        assertTrue(RemoteLogUpload.start(context))
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        assertFalse(RemoteLogUpload.start(context))
        L7Agreement.revoke(context)
        assertEquals(RemoteLogUpload.Phase.CANCELLED, RemoteLogUpload.status.phase)
        assertFalse(RemoteLogUpload.start(context))
        finish.countDown()
        Thread.sleep(150)
        assertEquals(RemoteLogUpload.Phase.CANCELLED, RemoteLogUpload.status.phase)
    }

    @Test fun http200WithPartialIngestionIsReportedAsFailure() {
        val endpoint = serve { exchange ->
            val response = acknowledgement("l7carplay", 0, 1)
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        val report = RemoteLogReport.create(listOf("Audio ready"), "test", "test")
        RemoteLogTransport().use {
            assertEquals(-1, it.send(RemoteLogConfig(endpoint, token), report.batches.first()))
        }
    }

    @Test fun configuredExampleStreamIsReplacedWithoutChangingServerOrOrganization() {
        val saved = RemoteLogConfig("https://logs.example/proxy/api/test_org/123/_json", token)
        val first = RemoteLogDevice.derive("android_id", "synthetic-a")
        val second = RemoteLogDevice.derive("android_id", "synthetic-b")
        assertEquals("https://logs.example/proxy/api/test_org/$first/_json", saved.forDevice(first).endpoint)
        assertEquals(token, saved.forDevice(first).authorization)
        assertEquals(first, saved.forDevice(first).stream)
        assertNotEquals(saved.forDevice(first).endpoint, saved.forDevice(second).endpoint)
        assertTrue(saved.forDevice(first).valid())
        assertTrue(runCatching { saved.forDevice("../other") }.isFailure)
    }

    @Test fun endpointTemplatesExpandOnlyWithinTheStreamAndMatchNormalizedAcknowledgements() {
        val name = RemoteLogDevice.derive("android_id", "synthetic", "Example", "Unit 42")
        val template = RemoteLogConfig("https://logs.example/proxy/api/test/{HeadUnit}-{DeviceID}/_json", token)
        assertTrue(template.valid())
        val target = template.forDevice(name)
        assertEquals("https://logs.example/proxy/api/test/example_unit_42-${name.takeLast(23)}/_json", target.endpoint)
        assertEquals(name, target.stream)
        val idOnly = RemoteLogConfig("https://logs.example/api/test/{DeviceID}/_json", token)
        assertTrue(idOnly.valid())
        assertEquals(name.takeLast(23), idOnly.forDevice(name).stream)
        assertFalse(RemoteLogConfig.validEndpoint("https://{HeadUnit}.example/api/test/{DeviceID}/_json"))
        assertFalse(RemoteLogConfig.validEndpoint("https://logs.example/api/test/{Unknown}/_json"))
    }

    private fun records(report: RemoteLogReport): JSONArray = JSONArray(report.batches.flatMap { batch ->
        val data = JSONArray(batch.body.toString(Charsets.UTF_8))
        (0 until data.length()).map { data.getJSONObject(it) }
    })

    private fun acknowledgement(stream: String, successful: Int, failed: Int): ByteArray = JSONObject()
        .put("code", 200).put("status", JSONArray().put(JSONObject().put("name", stream)
            .put("successful", successful).put("failed", failed))).toString().toByteArray()

    private fun serve(handler: (com.sun.net.httpserver.HttpExchange) -> Unit): String {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/", handler)
        http.start()
        server = http
        return "http://127.0.0.1:${http.address.port}/api/default/l7carplay/_json"
    }

    private fun awaitPhase(phase: RemoteLogUpload.Phase) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (RemoteLogUpload.status.phase == RemoteLogUpload.Phase.UPLOADING && System.nanoTime() < end) Thread.sleep(20)
        assertEquals(phase, RemoteLogUpload.status.phase)
    }
}
