package com.shilapi.xcertplay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageButton
import com.shilapi.xcertplay.host.R
import kotlin.math.abs

/** 收起时窗口只有图标大小，展开时只有菜单大小，桌面其他区域仍可操作。 */
internal class L7DesktopWindow(private val owner: Context, private val navigate: (String) -> Unit) {
    private val manager = owner.getSystemService(WindowManager::class.java)
    private var view: View? = null
    private var ui: Context = owner
    private var density = 0
    private var expanded = false
    private lateinit var params: WindowManager.LayoutParams
    private fun dp(value: Int) = L7Components.dp(ui, value)

    fun show() {
        if (view != null && density != L7UiDensity.value(owner)) remove()
        if (view == null) build()
        if (!expanded) view?.alpha = 1f - L7FloatingNavigationPreferences.transparency(owner) / 100f
    }

    fun hide() { remove(); expanded = false }
    fun refresh() { view?.let(L7Ui::refresh) }

    private fun remove() {
        view?.let { runCatching { manager.removeView(it) } }
        view = null
    }

    private fun build() {
        ui = L7UiDensity.wrap(AppLocale.wrap(owner))
        density = L7UiDensity.value(owner)
        params = WindowManager.LayoutParams(dp(if (expanded) 132 else 64),
            if (expanded) WindowManager.LayoutParams.WRAP_CONTENT else dp(64),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            title = "L7CarPlay 桌面菜单"
        }
        val current = if (expanded) menu() else handle()
        view = current
        if (expanded) { params.x = dp(16); params.y = dp(16) } else position()
        manager.addView(current, params)
    }

    private fun menu() = L7NavigationRail(ui) { target ->
        hide()
        navigate(target)
    }.apply {
        // 系统悬浮菜单仅在应用不可见时显示，当前位置属于车机桌面/外部界面。
        select("car-home")
        L7MenuSurface.navigation(this)
        setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                hide(); show(); true
            } else false
        }
    }

    private fun handle() = ImageButton(ui).apply {
        contentDescription = ui.getString(R.string.l7_desktop_expand)
        L7MenuSurface.handle(this)
        setOnClickListener { remove(); expanded = true; show() }
        attachDrag(this)
    }

    @Suppress("DEPRECATION")
    private fun size() = Point().also { manager.defaultDisplay.getSize(it) }
    private fun travel() = size().let { Point((it.x - dp(80)).coerceAtLeast(0), (it.y - dp(80)).coerceAtLeast(0)) }
    private fun position() {
        val saved = L7FloatingNavigationPreferences.position(owner)
        val travel = travel()
        params.x = dp(8) + (travel.x * saved.first).toInt()
        params.y = dp(8) + (travel.y * saved.second).toInt()
    }

    private fun attachDrag(handle: View) {
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        var dragging = false
        val slop = ViewConfiguration.get(ui).scaledTouchSlop
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = params.x; startY = params.y; dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX; val dy = event.rawY - downY
                    dragging = dragging || abs(dx) > slop || abs(dy) > slop
                    if (dragging) {
                        val travel = travel()
                        params.x = (startX + dx.toInt()).coerceIn(dp(8), dp(8) + travel.x)
                        params.y = (startY + dy.toInt()).coerceIn(dp(8), dp(8) + travel.y)
                        manager.updateViewLayout(handle, params)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        val travel = travel()
                        L7FloatingNavigationPreferences.savePosition(owner,
                            (params.x - dp(8)).toFloat() / travel.x.coerceAtLeast(1),
                            (params.y - dp(8)).toFloat() / travel.y.coerceAtLeast(1))
                    } else if (event.actionMasked == MotionEvent.ACTION_UP) handle.performClick()
                    dragging = false
                }
            }
            true
        }
    }
}
