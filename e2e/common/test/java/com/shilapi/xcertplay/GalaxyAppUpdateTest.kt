package com.shilapi.xcertplay

import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.shilapi.xcertplay.host.R
import java.io.File
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class GalaxyAppUpdateTest {
    private class Queue : AbstractExecutorService() {
        val tasks = ArrayDeque<Runnable>()
        private var stopped = false
        override fun execute(task: Runnable) { tasks.add(task) }
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> { stopped = true; return tasks.toMutableList().also { tasks.clear() } }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = stopped
        fun run() { tasks.removeFirst().run(); shadowOf(Looper.getMainLooper()).idle() }
    }
    private class Source : GalaxyUpdateSource {
        var checks = 0
        var downloads = 0
        var fail = false
        var beforeReturn: () -> Unit = {}
        var code = 147L
        override fun latest(packageName: String, request: GalaxyUpdateRequest): GalaxyUpdateRelease {
            checks++
            if (fail) throw GalaxyUpdateException(GalaxyUpdateFailure.NETWORK)
            beforeReturn()
            return GalaxyUpdateManifest.parse(updateManifest(pkg = packageName).put("versionCode", code)
                .also { it.getJSONObject("file").put("url", "/downloads/com-ecarx-carplay/v$code/a.apk") }.toString(),
                GalaxyUpdateManifest.endpoint, packageName)
        }
        override fun download(release: GalaxyUpdateRelease, directory: File, request: GalaxyUpdateRequest, progress: (Long) -> Unit): File {
            downloads++; directory.mkdirs()
            return File(directory, "download-test.apk").apply { writeBytes(byteArrayOf(1, 2, 3)); progress(3) }
        }
    }
    private fun texts(view: View): String = (if (view is TextView) view.text.toString() else "") +
        if (view is ViewGroup) (0 until view.childCount).joinToString("\n") { texts(view.getChildAt(it)) } else ""
    private fun dialog() = ShadowAlertDialog.getLatestAlertDialog()
    private fun fixture(test: (GalaxyAppUpdate, Queue, Source, ComponentActivity, () -> Unit) -> Unit) {
        val host = Robolectric.buildActivity(ComponentActivity::class.java).create()
        val activity = host.get().apply { setTheme(android.R.style.Theme_Material_Light_NoActionBar) }
        @Suppress("DEPRECATION")
        val installed = activity.packageManager.getPackageInfo(activity.packageName, 0).apply { versionCode = 146 }
        shadowOf(activity.packageManager).installPackage(installed)
        GalaxyUpdateCache.clear(activity)
        val queue = Queue(); val source = Source()
        val update = GalaxyAppUpdate(activity, source, queue) { _, _, token -> token.check() }
        host.start().resume()
        try { test(update, queue, source, activity) { host.pause().stop() } }
        finally { host.pause().stop().destroy(); GalaxyUpdateCache.clear(activity) }
    }
    @Test fun newReleaseRequiresExplicitDownloadThenExplicitInstall() = fixture { update, queue, source, activity, _ ->
        update.check(); update.check(); assertEquals(1, queue.tasks.size)
        queue.run()
        assertTrue(texts(dialog().window!!.decorView).contains(activity.getString(R.string.update_available, "0.1.119")))
        assertEquals(0, source.downloads)
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick(); queue.run()
        assertEquals(1, source.downloads)
        assertEquals(activity.getString(R.string.update_install), dialog().getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        assertNull(shadowOf(activity).nextStartedActivity)
    }
    @Test fun networkFailureCanBeRetriedInSameWindow() = fixture { update, queue, source, _, _ ->
        source.fail = true; update.check(); queue.run()
        val window = dialog()
        source.fail = false
        window.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); queue.run()
        assertEquals(2, source.checks); assertSame(window, dialog())
    }
    @Test fun closingDuringCheckRejectsLateResults() = fixture { update, queue, _, _, _ ->
        update.check()
        val window = dialog(); window.dismiss(); queue.run()
        assertFalse(window.isShowing)
        assertEquals(0, queue.tasks.size)
    }
    @Test fun backgroundingRejectsPendingResultsAndDoesNotResumeDownload() = fixture { update, queue, source, activity, background ->
        update.check(); background(); queue.run()
        assertTrue(texts(dialog().window!!.decorView).contains(activity.getString(R.string.update_stopped)))
        assertEquals(0, source.downloads)
        assertNull(shadowOf(activity).nextStartedActivity)
    }
    @Test fun recreatedActivityRestoresVerifiedApkWithoutAutomaticInstallation() {
        val host = Robolectric.buildActivity(ComponentActivity::class.java).create()
        val activity = host.get().apply { setTheme(android.R.style.Theme_Material_Light_NoActionBar) }
        @Suppress("DEPRECATION")
        val installed = activity.packageManager.getPackageInfo(activity.packageName, 0).apply { versionCode = 146 }
        shadowOf(activity.packageManager).installPackage(installed)
        val source = Source(); val token = GalaxyUpdateRequest()
        val release = source.latest(activity.packageName, token)
        val file = source.download(release, File(activity.cacheDir, "app-updates"), token) {}
        GalaxyUpdateCache.save(activity, file, release)
        assertTrue(L7Agreement.accept(activity))
        val queue = Queue(); var verified = 0
        GalaxyAppUpdate(activity, source, queue) { candidate, value, request ->
            assertEquals(file, candidate); assertEquals(release, value); request.check(); verified++
        }
        try {
            host.start().resume(); assertEquals(1, queue.tasks.size); queue.run()
            assertEquals(1, verified)
            assertEquals(activity.getString(R.string.update_install), dialog().getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
            assertNull(shadowOf(activity).nextStartedActivity)
            assertEquals(1, source.downloads)
        } finally {
            host.pause().stop().destroy(); GalaxyUpdateCache.clear(activity)
            activity.getSharedPreferences("l7_agreement", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
    @Test fun sameOrOlderStoreVersionDoesNotOfferDownload() = fixture { update, queue, source, activity, _ ->
        @Suppress("DEPRECATION")
        val installed = activity.packageManager.getPackageInfo(activity.packageName, 0).longVersionCode
        source.code = installed.coerceAtLeast(1)
        update.check(); queue.run()
        assertEquals(View.GONE, dialog().getButton(AlertDialog.BUTTON_POSITIVE).visibility)
        assertEquals(0, source.downloads)
    }
}
