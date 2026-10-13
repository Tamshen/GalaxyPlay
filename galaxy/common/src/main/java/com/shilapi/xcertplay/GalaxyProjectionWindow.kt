package com.shilapi.xcertplay

import android.graphics.Point
import android.graphics.Rect
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** 系统栏只占用实际重叠的区域；画面与触控一起布局，连接等待 Insets 与布局完成。 */
internal class GalaxyProjectionWindow(
    private val root: FrameLayout,
    private val report: (String) -> Unit = {},
    private val settled: () -> Unit,
) {
    private var hideTop = true
    private var hideBottom = true
    private var insets: WindowInsetsCompat? = null
    private var lastGeometry: String? = null
    var ready = false
        private set
    private val layout = ViewTreeObserver.OnGlobalLayoutListener { apply() }

    init {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, value ->
            insets = value
            apply()
            value
        }
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                root.viewTreeObserver.addOnGlobalLayoutListener(layout)
                ViewCompat.requestApplyInsets(root)
            }
            override fun onViewDetachedFromWindow(view: View) {
                root.viewTreeObserver.removeOnGlobalLayoutListener(layout)
                ready = false
                insets = null
            }
        })
    }

    fun configure(hideTop: Boolean, hideBottom: Boolean) {
        if (this.hideTop != hideTop || this.hideBottom != hideBottom) {
            ready = false
            insets = null
        }
        this.hideTop = hideTop
        this.hideBottom = hideBottom
        ViewCompat.requestApplyInsets(root)
    }

    @Suppress("DEPRECATION")
    private fun apply() {
        val value = insets ?: return
        if (root.width <= 0 || root.height <= 0 || root.isLayoutRequested) return
        val display = Point()
        root.display?.getRealSize(display)
        if (display.x <= 0 || display.y <= 0) return
        // 忽略暂时隐藏/展开的动画，按用户选择的栏位预留，避免连接后才缩小画面。
        val top = if (hideTop) Insets.NONE else value.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars())
        val bottom = if (hideBottom) Insets.NONE else value.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars())
        val bars = Insets.max(Insets.max(top, bottom), value.getInsets(WindowInsetsCompat.Type.displayCutout()))
        val position = IntArray(2).also(root::getLocationOnScreen)
        val bounds = Rect(position[0], position[1], position[0] + root.width, position[1] + root.height)
        val padding = overlap(bounds, display.x, display.y, bars)
        if (root.paddingLeft != padding.left || root.paddingTop != padding.top ||
            root.paddingRight != padding.right || root.paddingBottom != padding.bottom) {
            ready = false
            root.setPadding(padding.left, padding.top, padding.right, padding.bottom)
            return
        }
        if (padding.left + padding.right >= root.width || padding.top + padding.bottom >= root.height) {
            ready = false
            return
        }
        ready = true
        val geometry = "ProjectionSystemBars: hideTop=$hideTop hideBottom=$hideBottom " +
            "panel=${display.x}x${display.y} root=${root.width}x${root.height} " +
            "origin=${position[0]},${position[1]} padding=$padding " +
            "available=${root.width - padding.left - padding.right}x${root.height - padding.top - padding.bottom}"
        if (lastGeometry != geometry) {
            lastGeometry = geometry
            runCatching { report(geometry) }
        }
        settled()
    }

    companion object {
        /** 原厂已缩小窗口时不再扣一次系统栏；也支持横屏右侧导航栏。 */
        fun overlap(bounds: Rect, displayWidth: Int, displayHeight: Int, bars: Insets): Insets = Insets.of(
            (bars.left - bounds.left).coerceIn(0, bounds.width()),
            (bars.top - bounds.top).coerceIn(0, bounds.height()),
            (bounds.right - (displayWidth - bars.right)).coerceIn(0, bounds.width()),
            (bounds.bottom - (displayHeight - bars.bottom)).coerceIn(0, bounds.height()),
        )
    }
}
