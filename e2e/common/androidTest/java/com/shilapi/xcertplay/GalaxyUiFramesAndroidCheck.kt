package com.shilapi.xcertplay

import android.animation.ValueAnimator
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import org.json.JSONObject

/** 用有限 UI 动画验证真实窗口帧回调；不把 Choreographer 的主动回调当作产品 UFPS。 */
internal object GalaxyUiFramesAndroidCheck {
    fun run(test: Instrumentation, activity: GalaxyPerformanceHarnessActivity): JSONObject {
        val view = object : View(activity) {
            var color = 0
            override fun onDraw(canvas: Canvas) { canvas.drawColor(Color.rgb(color, 80, 100)) }
        }
        lateinit var animator: ValueAnimator
        test.runOnMainSync {
            activity.content.addView(view, FrameLayout.LayoutParams(300, 200))
            animator = ValueAnimator.ofInt(0, 255).apply {
                duration = 4000
                addUpdateListener { view.color = it.animatedValue as Int; view.invalidate() }
                start()
            }
        }
        val active: GalaxyPerformanceSample
        try {
            waitFor { (GalaxyPerformanceState.latest?.uiFps ?: 0.0) > 5.0 }
            active = GalaxyPerformanceState.latest!!
            check(!active.input.carPlayConnected && active.main == null) { "UFPS_LEAKED_INTO_CFPS" }
        } finally { test.runOnMainSync { animator.cancel(); activity.content.removeView(view) } }
        waitFor { GalaxyPerformanceState.latest?.uiState == GalaxyUiFpsState.STATIC }
        test.runOnMainSync { check(activity.moveTaskToBack(true)) }
        waitFor { GalaxyPerformanceState.latest?.uiState == GalaxyUiFpsState.BACKGROUND }
        test.runOnMainSync {
            activity.startActivity(Intent(activity, GalaxyPerformanceHarnessActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
        waitFor { GalaxyPerformanceState.latest?.uiState !in setOf(null, GalaxyUiFpsState.BACKGROUND) }
        return JSONObject().put("animatedUfps", active.uiFps).put("cfpsIndependent", true)
            .put("static", true).put("background", true).put("resume", true)
    }
    private fun waitFor(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 6000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(25)
        check(condition()) { "UFPS_STATE_TIMEOUT" }
    }
}
