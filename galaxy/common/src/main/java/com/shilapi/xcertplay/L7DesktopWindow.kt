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

/** 程序外仅保留可拖动的返回图标，单击直接回到画面，其他区域仍可操作。 */
internal class L7DesktopWindow(private val owner: Context, private val returnToDisplay: () -> Unit) {
    private val manager = owner.getSystemService(WindowManager::class.java)
    private var view: View? = null
    private var ui: Context = owner
    private var density = 0
    private lateinit var params: WindowManager.LayoutParams
    private fun dp(value: Int) = L7Components.dp(ui, value)

    fun show() {
        if (view != null && density != L7UiDensity.value(owner)) remove()
        if (view == null) build()
        view?.alpha = 1f - L7FloatingNavigationPreferences.transparency(owner) / 100f
    }

    fun hide() { remove() }
    fun refresh() { view?.let(L7Ui::refresh) }

    private fun remove() {
        view?.let { runCatching { manager.removeView(it) } }
        view = null
    }

    private fun build() {
        ui = L7UiDensity.wrap(AppLocale.wrap(owner))
        density = L7UiDensity.value(owner)
        params = WindowManager.LayoutParams(dp(64), dp(64),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            title = ui.getString(R.string.l7_desktop_title)
        }
        val current = handle()
        view = current
        position()
        manager.addView(current, params)
    }

    private fun handle() = ImageButton(ui).apply {
        contentDescription = ui.getString(R.string.l7_desktop_return)
        L7MenuSurface.handle(this)
        setOnClickListener { hide(); returnToDisplay() }
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
