package com.shilapi.xcertplay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.shilapi.xcertplay.host.R

/** 连接动作位于画面下方，详细功能统一归入设置。 */
internal class L7NavigationRail(context: Context, onSelect: (String) -> Unit) : LinearLayout(context) {
    private val buttons = mutableMapOf<String, View>()
    private var connected = false
    private val connectionDot = View(context).apply {
        visibility = View.INVISIBLE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        L7Ui.bind(this) {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(context.getColor(R.color.product_ui_connected))
                setStroke(L7Components.dp(context, 2), context.getColor(R.color.product_ui_surface))
            }
        }
    }

    init {
        val resources = context.resources
        fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
        orientation = VERTICAL
        setBackgroundColor(Color.TRANSPARENT)
        setPadding(0, dp(10), 0, dp(10))
        val navigation = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val items = listOf(
            Triple("home", R.string.l7_nav_picture, R.drawable.ic_l7_projection),
            Triple("connection", R.string.l7_nav_connect, R.drawable.ic_l7_connection),
            Triple("settings", R.string.settings, R.drawable.ic_l7_settings),
            Triple("car-home", R.string.l7_nav_car_home, R.drawable.ic_l7_home),
            Triple("exit", R.string.l7_nav_exit, R.drawable.ic_l7_exit),
        )
        for ((key, title, icon) in items) {
            val item = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                minimumHeight = resources.getDimensionPixelSize(R.dimen.l7_nav_item_height)
                setPadding(dp(4), dp(13), dp(4), dp(13))
                L7Ui.bind(this) {
                    background = RippleDrawable(ColorStateList.valueOf(context.getColor(R.color.product_ui_ripple)),
                        L7Ui.rounded(context, if (isSelected) context.getColor(R.color.product_ui_selected) else Color.TRANSPARENT,
                            4), null)
                }
                isFocusable = true
                isClickable = true
                isSelected = key == "home"
                contentDescription = context.getString(title)
                setOnClickListener { onSelect(key) }
            }
            val iconSize = resources.getDimensionPixelSize(R.dimen.l7_nav_icon_size)
            fun foreground() = context.getColor(when {
                key == "home" && connected -> R.color.product_ui_connected
                item.isSelected -> R.color.product_ui_accent
                else -> R.color.product_ui_text
            })
            val iconFrame = FrameLayout(context)
            iconFrame.addView(ImageView(context).apply {
                setImageResource(if (key == "connection" && connected) R.drawable.ic_l7_refresh else icon)
                L7Ui.bind(this) {
                    if (key == "connection") setImageResource(if (connected) R.drawable.ic_l7_refresh else icon)
                    imageTintList = ColorStateList.valueOf(foreground())
                }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(-1, -1))
            // 状态点覆盖在图标角上，不挤动图标或文字，也不改变菜单尺寸。
            if (key == "home") iconFrame.addView(connectionDot,
                FrameLayout.LayoutParams(dp(13), dp(13), Gravity.TOP or Gravity.END))
            item.addView(iconFrame, LinearLayout.LayoutParams(iconSize, iconSize))
            item.addView(TextView(context).apply {
                setText(title)
                textSize = 19f
                gravity = Gravity.CENTER
                L7Ui.bind(this) { setTextColor(foreground()) }
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
            navigation.addView(item, LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(dp(8), 0, dp(8), dp(6))
            })
            buttons[key] = item
        }
        // 小窗口和大字体时侧栏可独立滚动，保持所有入口可达。
        addView(ScrollView(context).apply { addView(navigation) }, LinearLayout.LayoutParams(-1, -2))
        L7Ui.refresh(this)
    }

    fun setConnected(value: Boolean) {
        if (connected == value) return
        connected = value
        connectionDot.visibility = if (value) View.VISIBLE else View.INVISIBLE
        buttons["home"]?.let {
            it.contentDescription = context.getString(if (value) R.string.l7_nav_picture_connected else R.string.l7_nav_picture)
            L7Ui.refresh(it)
        }
        buttons["connection"]?.let { item ->
            val title = context.getString(if (value) R.string.l7_nav_reconnect else R.string.l7_nav_connect)
            item.contentDescription = title
            ((item as ViewGroup).getChildAt(1) as TextView).text = title
            L7Ui.refresh(item)
        }
    }

    fun select(destination: String) {
        // 页面切换只更新选中态，复用原有按钮和布局。
        buttons.forEach { (key, button) -> button.isSelected = key == destination }
        L7Ui.refresh(this)
    }
}
