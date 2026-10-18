package com.shilapi.xcertplay

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject

/** 真实 Android VM 的窗口／线程与四种空闲模式对照；不冒充实车投屏负载或收益。 */
object GalaxyPerformanceAndroidChecks {
    @JvmStatic fun run(context: Context, test: Instrumentation) {
        check(Build.VERSION.SDK_INT == 30 && Build.HARDWARE in setOf("ranchu", "goldfish"))
        check(context.packageName.endsWith(".test"))
        check(Settings.canDrawOverlays(context)) { "TEST_OVERLAY_PERMISSION_REQUIRED" }
        check(!CarPlayBackgroundSession.hasSession())
        val activity = test.startActivitySync(Intent(context, GalaxyPerformanceHarnessActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as GalaxyPerformanceHarnessActivity
        val results = JSONArray()
        fun status(value: String) { test.sendStatus(0, Bundle().apply { putString("stream", "PERFORMANCE_CHECK $value") }) }
        try {
            status("OFF")
            test.runOnMainSync { GalaxyPerformanceService.stop(context) }
            waitFor { !GalaxyPerformanceState.running && workers() == 0 }
            results.put(measure("OFF", 15_000))
            check(workers() == 0); check(GalaxyUiFrameMonitor.read() == null)
            status("BASIC")
            test.runOnMainSync { GalaxyPerformanceService.overlay(context, false); GalaxyPerformanceService.start(context).getOrThrow() }
            waitFor { GalaxyPerformanceState.running && GalaxyPerformanceState.latest != null }
            check(!GalaxyPerformanceState.overlayVisible)
            results.put(measure("BASIC", 15_000))
            check(GalaxyPerformanceState.latest!!.cpuCorePercent != null)
            status("UFPS")
            val uiFrames = GalaxyUiFramesAndroidCheck.run(test, activity)
            status("OVERLAY")
            test.runOnMainSync { GalaxyPerformanceService.overlay(context, true) }
            waitFor { GalaxyPerformanceState.overlayVisible && GalaxyPerformanceState.overlayBounds != null }
            stablePosition()
            touchThrough(activity, test)
            check(activity.hasWindowFocus())
            results.put(measure("OVERLAY", 15_000))
            status("DEEP")
            test.runOnMainSync { GalaxyPerformanceService.deep(context) }
            waitFor { GalaxyPerformanceState.latest?.deep == true }
            results.put(measure("DEEP", 13_000))
            check(GalaxyPerformanceState.history.any { it.threadCpu.isNotEmpty() }) { "NO_THREAD_SAMPLES" }
            waitFor(5000) { GalaxyPerformanceState.latest?.deep == false }
            test.runOnMainSync { GalaxyPerformanceService.stop(context) }
            waitFor { !GalaxyPerformanceState.running && !GalaxyPerformanceState.overlayVisible && workers() == 0 }
            check(!com.shilapi.xcertplay.media.GalaxyPerformanceObservation.active)
            status("VIDEO")
            val video = GalaxyPerformanceVideoCheck.run(context, test, activity)
            status("BINDER")
            GalaxyHardKeyAndroidCheck.run(context, test)
            File(context.cacheDir, "performance-android-checks.json").writeText(JSONObject()
                .put("workload", "ANDROID11_AVD_IDLE_HARNESS")
                .put("modes", results).put("video", video).put("ui", uiFrames)
                .put("touchThrough", true).put("noFocusSteal", true).put("workerReleased", workers() == 0)
                .put("binderParcel", true).toString(2))
            status("COMPLETE")
        } finally {
            test.runOnMainSync { GalaxyPerformanceService.stop(context); activity.finish() }
            waitFor { workers() == 0 }
        }
    }

    private fun measure(mode: String, millis: Long): JSONObject {
        val cpu = Process.getElapsedCpuTime(); val start = SystemClock.elapsedRealtime()
        Thread.sleep(millis)
        check(AsyncDiagnosticLog.awaitIdle(2000))
        val elapsed = SystemClock.elapsedRealtime() - start
        val used = Process.getElapsedCpuTime() - cpu
        val memory = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        val result = JSONObject().put("mode", mode).put("elapsedMs", elapsed).put("processCpuMs", used)
            .put("cpuCorePercent", used * 100.0 / elapsed).put("pssKiB", memory.totalPss)
            .put("javaBytes", Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() })
            .put("nativeBytes", Debug.getNativeHeapAllocatedSize())
        GalaxyPerformanceState.latest?.let {
            result.put("queryNs", it.sampleNs).put("queryCpuNs", it.sampleCpuNs)
                .put("displayNs", it.displayNs).put("memoryAgeMs", it.input.memoryAgeMs)
        }
        return result
    }
    private fun workers() = Thread.getAllStackTraces().keys.count { it.isAlive && it.name in setOf("galaxy-performance", "galaxy-ui-frames") }
    private fun waitFor(timeout: Long = 5000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!condition() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(25)
        check(condition()) { "PERFORMANCE_STATE_TIMEOUT" }
    }
    private fun stablePosition() {
        val initial = android.graphics.Rect(GalaxyPerformanceState.overlayBounds!!)
        repeat(20) {
            Thread.sleep(50)
            val current = GalaxyPerformanceState.overlayBounds!!
            check(initial.left == current.left && initial.top == current.top) { "OVERLAY_POSITION_OSCILLATED" }
        }
    }
    private fun touchThrough(activity: GalaxyPerformanceHarnessActivity, test: Instrumentation) {
        val touches = AtomicInteger()
        val target = View(activity).apply { setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) touches.incrementAndGet()
            true
        } }
        test.runOnMainSync { activity.content.addView(target, FrameLayout.LayoutParams(-1, -1)) }
        waitFor { target.isLaidOut }
        try {
            val bars = activity.getSystemService(WindowManager::class.java).currentWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val rect = GalaxyPerformanceState.overlayBounds!!
            check(rect.left >= bars.left && rect.top >= bars.top && rect.width() > 0 && rect.height() > 0)
            for ((x, y) in listOf((rect.left + 1).toFloat() to (rect.top + 1).toFloat(),
                rect.exactCenterX() to rect.exactCenterY())) {
                val now = SystemClock.uptimeMillis()
                MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0).let { test.sendPointerSync(it); it.recycle() }
                MotionEvent.obtain(now, now + 20, MotionEvent.ACTION_UP, x, y, 0).let { test.sendPointerSync(it); it.recycle() }
            }
            waitFor { touches.get() == 2 }
        } finally { test.runOnMainSync { activity.content.removeView(target) } }
    }
}
