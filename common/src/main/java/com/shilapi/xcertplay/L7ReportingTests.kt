package com.shilapi.xcertplay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** 只持应用上下文；页面重绘或临时去查看原车界面不会丢失测试，最长两分钟自动结束。 */
internal object L7ReportingTests {
    private val handler = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private val controller = L7ReportingTestController(
        factory = { kind, log, released, current ->
            val context = AppLocale.wrap(requireNotNull(app))
            when (kind) {
                L7ReportingKind.MEDIA -> L7ManualMediaReport(context, log, released, current)
                L7ReportingKind.NAVIGATION -> L7ManualNavigationReport(context, log, released, current)
            }
        },
        blocked = { L7AppExit.exiting || CarPlayBackgroundSession.hasSession() ||
            app?.let { !L7Agreement.canUse(it) } != false },
        clock = SystemClock::elapsedRealtime, log = L7DebugLog::record,
    )
    private val timeout = object : Runnable {
        override fun run() {
            controller.expire()
            if (snapshot().phase == L7ReportingTestController.Phase.RUNNING) handler.postDelayed(this, 1000)
        }
    }
    fun start(context: Context, kind: L7ReportingKind): Boolean {
        app = context.applicationContext
        L7DebugLog.initialize(requireNotNull(app))
        return controller.start(kind).also { if (it) {
            handler.removeCallbacks(timeout)
            handler.postDelayed(timeout, 1000)
        } }
    }
    fun snapshot() = controller.snapshot()
    fun action(kind: L7ReportingKind, value: L7ReportingAction) = controller.action(kind, value)
    fun observe(kind: L7ReportingKind, visible: Boolean) = controller.observe(kind, visible)
    fun stop(reason: String) { controller.stop(reason); handler.removeCallbacks(timeout) }
}
