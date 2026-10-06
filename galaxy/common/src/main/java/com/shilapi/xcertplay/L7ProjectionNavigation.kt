package com.shilapi.xcertplay

import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R
import kotlin.math.abs

/** 投屏上方的导航浮层；改变可见性不挤压 Surface，也不截获收起状态下的画面触控。 */
internal class L7ProjectionNavigation(
    context: Context,
    private val releaseTouches: () -> Unit,
    navigate: (String) -> Unit,
) : FrameLayout(context) {
    companion object {
        private const val MENU_WIDTH = 132
        private const val MENU_INSET = 16
        fun settingsColumnWidth(context: Context) = L7Components.dp(context, MENU_WIDTH + MENU_INSET * 2)
    }

    private val scrim = View(context)
    private val panel = LinearLayout(context)
    private val navigation = L7NavigationRail(context) { destination ->
        if (destination != "exit") collapse()
        navigate(destination)
    }
    internal val handle = ImageButton(context)
    private var settingsPage = false
    private var connected = false
    internal var expanded = true
        private set
    private var position = L7FloatingNavigationPreferences.position(context)
    private var downX = 0f
    private var downY = 0f
    private var originX = 0f
    private var originY = 0f
    private var dragging = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private fun dp(value: Int) = L7Components.dp(context, value)

    init {
        scrim.setBackgroundColor(0x22000000)
        scrim.setOnClickListener { collapse() }
        addView(scrim, LayoutParams(-1, -1))
        panel.orientation = LinearLayout.VERTICAL
        panel.isClickable = true
        L7MenuSurface.navigation(panel)
        panel.addView(navigation, LinearLayout.LayoutParams(-1, -2))
        // 菜单固定在窗口左上角；只有收起后的入口图标跟随拖动位置。
        addView(panel, LayoutParams(dp(MENU_WIDTH), -2, Gravity.TOP or Gravity.START).apply {
            setMargins(dp(MENU_INSET), dp(MENU_INSET), dp(MENU_INSET), dp(MENU_INSET))
        })
        handle.apply {
            contentDescription = context.getString(R.string.l7_rail_expand)
            L7MenuSurface.handle(this)
            setOnClickListener { expand() }
            setOnTouchListener { _, event -> drag(event) }
        }
        addView(handle, LayoutParams(dp(64), dp(64)))
        refreshAppearance()
        showExpanded(true)
    }

    fun setConnected(value: Boolean) {
        if (connected == value) return
        connected = value
        navigation.setConnected(value)
        showExpanded(!value)
    }

    fun expand() = showExpanded(true)
    fun collapse() = showExpanded(false)
    fun refreshAppearance() {
        handle.alpha = 1f - L7FloatingNavigationPreferences.transparency(context) / 100f
        position = L7FloatingNavigationPreferences.position(context)
        positionHandle()
        L7Ui.refresh(this)
    }

    fun showPage(page: String) {
        val wasSettings = settingsPage
        settingsPage = L7Routes.navigation(page) == "settings"
        if (wasSettings != settingsPage) updatePanelLayout()
        navigation.select(if (settingsPage) "settings" else "home")
        showExpanded(if (wasSettings && !settingsPage) false else expanded)
    }

    private fun updatePanelLayout() {
        // 五个按钮复用原实例和坐标；设置页只将底板延伸为整高左栏。
        val inset = dp(MENU_INSET)
        panel.layoutParams = LayoutParams(
            if (settingsPage) settingsColumnWidth(context) else dp(MENU_WIDTH),
            if (settingsPage) -1 else -2, Gravity.TOP or Gravity.START,
        ).apply {
            val margin = if (settingsPage) 0 else inset
            setMargins(margin, margin, margin, margin)
        }
        val padding = if (settingsPage) inset else 0
        panel.setPadding(padding, padding, padding, padding)
        if (settingsPage) L7SettingsStyle.navigation(panel) else L7MenuSurface.navigation(panel)
    }

    private fun showExpanded(value: Boolean) {
        releaseTouches()
        expanded = value || settingsPage
        panel.visibility = if (expanded) VISIBLE else GONE
        // 设置左栏始终可用，右侧直接交给正文滚动与操作。
        scrim.visibility = if (expanded && !settingsPage) VISIBLE else GONE
        handle.visibility = if (expanded) GONE else VISIBLE
        positionHandle()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        positionHandle()
    }

    private fun travelX() = (width - dp(64) - dp(16)).coerceAtLeast(0).toFloat()
    private fun travelY() = (height - dp(64) - dp(16)).coerceAtLeast(0).toFloat()
    private fun positionHandle() {
        handle.x = dp(8) + travelX() * position.first
        handle.y = dp(8) + travelY() * position.second
    }

    private fun drag(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX; downY = event.rawY
                originX = handle.x; originY = handle.y
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                dragging = dragging || abs(dx) > slop || abs(dy) > slop
                if (dragging) {
                    handle.x = (originX + dx).coerceIn(dp(8).toFloat(), dp(8) + travelX())
                    handle.y = (originY + dy).coerceIn(dp(8).toFloat(), dp(8) + travelY())
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    position = (if (travelX() == 0f) 0f else (handle.x - dp(8)) / travelX()) to
                        (if (travelY() == 0f) 0f else (handle.y - dp(8)) / travelY())
                    L7FloatingNavigationPreferences.savePosition(context, position.first, position.second)
                } else if (event.actionMasked == MotionEvent.ACTION_UP) handle.performClick()
                dragging = false
            }
        }
        return true
    }
}
