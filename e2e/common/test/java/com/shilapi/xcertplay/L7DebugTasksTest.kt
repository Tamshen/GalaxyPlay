package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.os.Looper
import com.shilapi.xcertplay.host.R
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class L7DebugTasksTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private var server: HttpServer? = null
    private var tasks: L7DebugTasks? = null
    @After fun cleanup() { tasks?.dispose(false); RemoteLogUpload.cancel(); server?.stop(0) }
    private fun controller(): L7DebugTasks {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        return L7DebugTasks(activity) {}.also { tasks = it }
    }
    private fun tick() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
    private fun await(phase: RemoteLogUpload.Phase) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (RemoteLogUpload.status.phase == RemoteLogUpload.Phase.UPLOADING && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(phase, RemoteLogUpload.status.phase); tick()
    }
    private fun configure(reply: (JSONArray) -> Int) {
        RemoteLogUpload.cancel()
        L7Agreement.accept(app)
        L7DebugLog.buffer.append("synthetic task test")
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/") { exchange ->
            val records = JSONArray(exchange.requestBody.bufferedReader().use { it.readText() })
            val code = reply(records)
            val bytes = JSONObject().put("code", 200).put("status", JSONArray().put(JSONObject()
                .put("name", RemoteLogDevice.id(app)).put("successful", records.length()).put("failed", 0))).toString().toByteArray()
            runCatching { exchange.sendResponseHeaders(code, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) } }
        }
        http.start(); server = http
        RemoteLogConfig.save(app, RemoteLogConfig("http://127.0.0.1:${http.address.port}/api/test/123/_json", "Basic dGVzdDpwYXNz"))
    }

    @Test fun failedUploadReopensConfirmationWithoutSendingAndRetriesOnlyOnClick() {
        val ids = CopyOnWriteArrayList<String>()
        configure { rows -> ids += rows.getJSONObject(0).getString("report_id"); if (ids.size == 1) 503 else 200 }
        val tasks = controller()
        tasks.upload(); tasks.upload()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        await(RemoteLogUpload.Phase.FAILED)
        assertTrue(dialog.isShowing)
        assertEquals(1, ids.size)
        assertEquals(app.getString(R.string.l7_log_retry), dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); tick()
        tasks.upload(); tick()
        assertEquals(1, ids.size)
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await(RemoteLogUpload.Phase.SUCCESS)
        assertEquals(2, ids.size); assertEquals(ids[0], ids[1])
        assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing)
    }

    @Test fun backgroundStopsUploadAndResumeDoesNotRestartIt() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        configure { entered.countDown(); release.await(3, TimeUnit.SECONDS); 200 }
        try {
            val tasks = controller()
            tasks.upload()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            tasks.background()
            assertEquals(RemoteLogUpload.Phase.CANCELLED, RemoteLogUpload.status.phase)
            release.countDown(); Thread.sleep(100)
            tasks.resume(); tick()
            assertEquals(RemoteLogUpload.Phase.CANCELLED, RemoteLogUpload.status.phase)
        } finally { release.countDown() }
    }

    @Test fun emptyUploadShowsANormalResultWithoutRetryOrAnyHttpRequest() {
        val received = java.util.concurrent.atomic.AtomicInteger()
        configure { received.incrementAndGet(); 400 }
        java.io.File(app.filesDir, "logs").deleteRecursively()
        L7DebugLog.buffer.clear()
        controller().upload()
        await(RemoteLogUpload.Phase.EMPTY)
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(dialog.isShowing)
        assertEquals(android.view.View.GONE, dialog.getButton(AlertDialog.BUTTON_POSITIVE).visibility)
        val matches = ArrayList<android.view.View>()
        dialog.window!!.decorView.findViewsWithText(matches, app.getString(R.string.l7_log_empty_title), android.view.View.FIND_VIEWS_WITH_TEXT)
        assertTrue(matches.isNotEmpty())
        assertEquals(0, received.get())
    }
}
