package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R

/** 仅相邻设置行之间绘制细分隔线，不把标题、提示或操作按钮误分成条目。 */
internal class L7SettingsCard(context: Context) : LinearLayout(context) {
    private val separator = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        orientation = VERTICAL
        layoutParams = LayoutParams(-1, -2)
        clipToOutline = true
        L7SettingsStyle.card(this)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        separator.color = context.getColor(R.color.l7_settings_card_border)
        separator.strokeWidth = L7Components.dp(context, 1).coerceAtLeast(1).toFloat()
        var previous: View? = null
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == View.GONE) continue
            if (child is L7SettingRow && previous is L7SettingRow) {
                val start = (child.left + child.textInset).toFloat()
                val end = (child.right - L7Components.dp(context, 20)).toFloat()
                canvas.drawLine(start, child.top.toFloat(), end, child.top.toFloat(), separator)
            }
            previous = child
        }
    }
}
