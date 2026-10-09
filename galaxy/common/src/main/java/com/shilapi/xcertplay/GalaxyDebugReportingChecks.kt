package com.shilapi.xcertplay

import android.app.Activity
import com.shilapi.xcertplay.host.R

/** 样例、判断与清理分别绑定 run/step/phase；不可把接口返回当作显示成功。 */
internal object GalaxyDebugReportingChecks {
    fun build(activity: Activity): List<GalaxyDebugFlow.Check> = L7ReportingKind.entries.flatMap { kind ->
        val title = if (kind == L7ReportingKind.MEDIA) R.string.l7_report_media_title else R.string.l7_report_navigation_title
        val actions = if (kind == L7ReportingKind.MEDIA) listOf(null, L7ReportingAction.TRACK,
            L7ReportingAction.PLAY_PAUSE, L7ReportingAction.PROGRESS, L7ReportingAction.COVER)
            else listOf(null, L7ReportingAction.ROAD, L7ReportingAction.REFRESH)
        actions.map { action ->
            GalaxyDebugFlow.Check("REPORT_${kind}_${action ?: "START"}", title, R.string.full_debug_reporting) {
                val before = L7ReportingTests.snapshot()
                if (action != null && (before.kind != kind || before.phase != L7ReportingTestController.Phase.RUNNING))
                    return@Check GalaxyDebugChecks.unavailable(automatic = true)
                val started = if (action == null) L7ReportingTests.start(activity, kind)
                    else before.kind == kind && before.phase == L7ReportingTestController.Phase.RUNNING
                if (started && action != null) L7ReportingTests.action(kind, action)
                val began = GalaxyDebugChecks.now()
                var shown = L7ReportingTests.snapshot()
                object : GalaxyDebugFlow.Attempt {
                    override fun poll(): GalaxyDebugFlow.Evidence {
                        shown = L7ReportingTests.snapshot()
                        val ready = started && shown.kind == kind && shown.phase == L7ReportingTestController.Phase.RUNNING && shown.preview != null
                        return GalaxyDebugFlow.Evidence(question = ready || !started || shown.phase == L7ReportingTestController.Phase.STOPPED || GalaxyDebugChecks.now() - began > 10_000,
                            canConfirm = ready, unavailable = !started,
                            token = "${shown.run}:${shown.step}:${shown.phase}", detail = shown.preview?.text(activity).orEmpty())
                    }
                    override fun observe(normal: Boolean) = !started || shown.phase != L7ReportingTestController.Phase.RUNNING ||
                        L7ReportingTests.observe(kind, normal, shown.run, shown.step, shown.phase)
                    // 同一种上报样例连续更新；完整流程的 cleanup 项独占最终释放。
                    override fun stop() {}
                    override fun retry() { if (action == null && started) L7ReportingTests.stop("SUITE_RETRY") }
                    override fun released() = L7ReportingTests.snapshot().phase != L7ReportingTestController.Phase.STOPPING
                }
            }
        } + GalaxyDebugFlow.Check("REPORT_${kind}_CLEANUP", title, R.string.full_debug_cleanup) {
            val before = L7ReportingTests.snapshot()
            val own = before.kind == kind && before.run > 0
            val hadPreview = own && before.preview != null
            if (own) L7ReportingTests.stop("SUITE_CLEANUP")
            var shown = L7ReportingTests.snapshot()
            object : GalaxyDebugFlow.Attempt {
                override fun poll(): GalaxyDebugFlow.Evidence {
                    shown = L7ReportingTests.snapshot()
                    val released = own && shown.phase == L7ReportingTestController.Phase.STOPPED
                    return GalaxyDebugFlow.Evidence(question = hadPreview && released, canConfirm = hadPreview && released,
                        automatic = !own || released && !hadPreview, unavailable = !hadPreview,
                        token = "${shown.run}:${shown.phase}")
                }
                override fun observe(normal: Boolean) = !own || L7ReportingTests.observe(kind, normal, shown.run, shown.step, shown.phase)
                override fun stop() { if (own) L7ReportingTests.stop("SUITE_CLEANUP") }
                override fun released() = !own || L7ReportingTests.snapshot().phase == L7ReportingTestController.Phase.STOPPED
            }
        }
    }
}
