package com.shilapi.xcertplay

import android.content.Context
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R

/** 左右操作区固定等宽；未配置右侧动作时也保留位置，标题始终居中。 */
internal class L7Header(
    context: Context,
    title: String,
    backLabel: String = context.getString(R.string.l7_back_settings),
    onBack: () -> Unit,
) : LinearLayout(context) {
    private val right = FrameLayout(context)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val size = L7Components.dp(context, 64)
        minimumHeight = size
        addView(L7Components.iconButton(context, R.drawable.ic_l7_back, backLabel, onBack), LayoutParams(size, size))
        addView(L7Typography.text(context, title, L7Typography.Role.PAGE_TITLE).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(L7Components.dp(context, 8), 0, L7Components.dp(context, 8), 0)
        }, LayoutParams(0, -2, 1f))
        addView(right, LayoutParams(size, size))
    }

    fun setRightAction(icon: Int, label: String, onClick: () -> Unit) {
        right.removeAllViews()
        right.addView(L7Components.iconButton(context, icon, label, onClick), FrameLayout.LayoutParams(-1, -1))
    }
}
