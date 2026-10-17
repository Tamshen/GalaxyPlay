package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.TextView
import java.io.Closeable
import java.util.Locale

/** 两行只读窗口，整块区域无触控／焦点；坐标使用物理 Insets，文字沿用应用密度。 */
internal class GalaxyPerformanceOverlay(private val base: Context,
    private val boundsChanged: (Rect?) -> Unit = {}, private val cost: (Long) -> Unit) : Closeable {
    private val manager = base.getSystemService(WindowManager::class.java)
    private var text: TextView? = null
    private var ui = L7UiDensity.wrap(AppLocale.wrap(base))
    private var signature = ""
    private var legacyLeft = 0
    private var legacyTop = 0
    internal val layout = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.LEFT; alpha = 0.75f; title = "GalaxyPlay performance" }

    fun update(sample: GalaxyPerformanceSample): Boolean {
        val config = base.resources.configuration
        val next = "${L7UiDensity.value(base)}:${config.fontScale}:${config.uiMode}:${AppLocale.preference(base)}:${config.screenWidthDp}:${config.screenHeightDp}"
        if (text == null || next != signature) {
            close(); legacyLeft = 0; legacyTop = 0; ui = L7UiDensity.wrap(AppLocale.wrap(base)); signature = next
            val view = object : TextView(ui) {
                override fun onDraw(canvas: Canvas) {
                    val start = System.nanoTime()
                    try { super.onDraw(canvas) } finally { cost(System.nanoTime() - start) }
                }
            }.apply {
                typeface = Typeface.MONOSPACE; textSize = 12f
                setTextColor(Color.WHITE); setBackgroundColor(0xb0000000.toInt())
                setPadding(dp(6), dp(3), dp(6), dp(3)); includeFontPadding = false
                maxLines = 2; isFocusable = false; isClickable = false; isLongClickable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setHorizontallyScrolling(true)
                setOnApplyWindowInsetsListener { view, insets -> position(insets); view.post { reportBounds(view) }; insets }
                addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ -> reportBounds(view) }
            }
            text = view
            position(null)
            try { manager.addView(view, layout) } catch (_: Exception) { text = null; return false }
            view.requestApplyInsets()
        }
        val value = panelText(sample)
        if (text?.text?.toString() != value) text?.text = value
        return true
    }

    private fun position(supplied: WindowInsets?) {
        // 窗口自身 Insets 随窗口位置裁剪，不能反馈为下一次绝对坐标，否则会每帧上下振荡。
        val insets = if (Build.VERSION.SDK_INT >= 30) runCatching { manager.currentWindowMetrics.windowInsets }.getOrNull() else supplied
        val left: Int
        val top: Int
        if (Build.VERSION.SDK_INT >= 30 && insets != null) {
            val bars = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            left = bars.left; top = bars.top
        } else {
            @Suppress("DEPRECATION")
            legacyLeft = maxOf(legacyLeft, insets?.stableInsetLeft ?: 0, insets?.systemWindowInsetLeft ?: 0)
            left = legacyLeft
            @Suppress("DEPRECATION")
            legacyTop = maxOf(legacyTop, insets?.stableInsetTop ?: 0, insets?.systemWindowInsetTop ?: 0)
            top = legacyTop
        }
        val x = left + dp(6); val y = top + dp(6)
        if (layout.x == x && layout.y == y) return
        layout.x = x; layout.y = y
        text?.takeIf { it.isAttachedToWindow }?.let { runCatching { manager.updateViewLayout(it, layout) } }
    }
    private fun dp(value: Int) = (value * ui.resources.displayMetrics.density).toInt()
    private fun reportBounds(view: View) {
        if (text !== view || !view.isAttachedToWindow) return
        val position = IntArray(2)
        view.getLocationOnScreen(position)
        runCatching { boundsChanged(Rect(position[0], position[1], position[0] + view.width, position[1] + view.height)) }
    }
    override fun close() {
        val view = text ?: return
        runCatching { manager.removeViewImmediate(view) }
        if (!view.isAttachedToWindow) { text = null; runCatching { boundsChanged(null) } }
        else L7DebugLog.record("PERFORMANCE WINDOW release=UNKNOWN")
    }
    companion object {
        fun panelText(sample: GalaxyPerformanceSample): String {
            fun number(value: Double?, width: Int, fraction: Boolean = false): String =
                (value?.let { String.format(Locale.US, if (fraction) "%.1f" else "%.0f", it) } ?: "--").padStart(width)
            val p = sample.input
            val memory = p.pssKiB?.let { "PSS ${number(it / 1024.0, 5)} MiB" }
                ?: "J ${number(p.javaBytes?.div(1048576.0), 4)}M  N ${number(p.nativeBytes?.div(1048576.0), 4)}M"
            return "FPS ${number(sample.main?.presentedFps, 5, true)} CPU ${number(sample.cpuCorePercent, 4)}%\n$memory"
        }
    }
}
