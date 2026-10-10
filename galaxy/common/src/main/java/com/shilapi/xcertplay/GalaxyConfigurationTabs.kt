package com.shilapi.xcertplay

import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R

/** 单行分类栏独立横向滚动；选中项和键盘焦点进入可见区域，不影响参数草稿。 */
internal class GalaxyConfigurationTabs(context: Context, select: (Int) -> Unit) : HorizontalScrollView(context) {
    private val row = LinearLayout(context)
    val buttons = mutableListOf<Button>()
    private var selected = 0
    private fun dp(value: Int) = L7Components.dp(context, value)

    init {
        isHorizontalScrollBarEnabled = false
        isFillViewport = false
        addView(row)
        listOf(R.string.config_page_common, R.string.config_page_connection, R.string.config_page_audio,
            R.string.config_page_video, R.string.config_page_more).forEachIndexed { index, title ->
            val button = L7Components.actionButton(context, context.getString(title)) { select(index) }.apply {
                textSize = 18f
                minWidth = dp(112)
                minimumWidth = minWidth
                setSingleLine()
                setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null)
            }
            buttons += button
            row.addView(button, LinearLayout.LayoutParams(-2, -2))
        }
        // 各段等宽并连续排列，窄窗口只横向滚动，不压缩触摸区域或换行。
        val width = buttons.maxOf { kotlin.math.ceil(it.paint.measureText(it.text.toString())).toInt() + dp(32) }
            .coerceAtLeast(dp(112))
        buttons.forEach { it.layoutParams.width = width }
    }

    fun category(index: Int) {
        selected = index
        buttons.forEachIndexed { position, button ->
            button.isSelected = position == index
            L7Ui.button(button, primary = button.isSelected, radius = 2,
                surface = R.color.product_ui_surface, withIcon = false)
            button.textSize = 18f
        }
        post { revealSelected() }
    }

    private fun revealSelected() {
        val tab = row.getChildAt(selected) ?: return
        if (tab.left < scrollX) smoothScrollTo(tab.left, 0)
        else if (tab.right > scrollX + width) smoothScrollTo(tab.right - width, 0)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post { revealSelected() }
    }
}
