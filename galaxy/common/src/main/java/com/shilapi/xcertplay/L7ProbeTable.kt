package com.shilapi.xcertplay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.host.R

/** 全量结果共用一张紧凑表；固定列比例，行高随字号增加，不使用独立设置卡片。 */
internal class L7ProbeTable(context: Context, items: List<L7ProbeItem>, onItem: (L7ProbeItem) -> Unit) : LinearLayout(context) {
    private val labels = L7ProbeLabels(context)
    private val separator = Paint(Paint.ANTI_ALIAS_FLAG)
    private fun dp(value: Int) = L7Components.dp(context, value)

    init {
        orientation = VERTICAL
        clipToOutline = true
        L7SettingsStyle.card(this)
        addView(row().apply {
            minimumHeight = dp(40)
            addView(cell(context.getString(R.string.l7_probe_column_item), true), column(.36f))
            addView(cell(context.getString(R.string.l7_probe_column_status), true), column(.22f))
            addView(cell(context.getString(R.string.l7_probe_column_reason), true), column(.42f))
            isAccessibilityHeading = true
        })
        items.forEach { item ->
            val status = L7ProbeStatus.of(item)
            addView(row().apply {
                addView(cell(labels.name(item)), column(.36f))
                addView(LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(ImageView(context).apply {
                        setImageResource(status.icon)
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        L7Ui.bind(this) { imageTintList = ColorStateList.valueOf(context.getColor(status.color)) }
                    }, LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(4) })
                    addView(cell(context.getString(status.label)).apply { L7Ui.text(this, status.color) }, LayoutParams(0, -2, 1f))
                }, column(.22f))
                addView(cell(labels.compactReason(item), true), column(.42f))
                contentDescription = "${labels.name(item)} · ${context.getString(status.label)} · ${labels.reason(item)}"
                isFocusable = true
                L7Ui.rowFeedback(this)
                setOnClickListener { onItem(item) }
            })
        }
    }

    private fun row() = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(48)
        setPaddingRelative(dp(12), dp(7), dp(8), dp(7))
    }

    private fun column(weight: Float) = LayoutParams(0, -2, weight)
    private fun cell(value: String, secondary: Boolean = false) = TextView(context).apply {
        text = value
        textSize = 14f
        includeFontPadding = false
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        setPaddingRelative(0, 0, dp(8), 0)
        L7Ui.text(this, if (secondary) R.color.product_ui_muted else R.color.product_ui_text)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        separator.color = context.getColor(R.color.l7_settings_card_border)
        separator.strokeWidth = dp(1).coerceAtLeast(1).toFloat()
        for (index in 1 until childCount) {
            val y = getChildAt(index).top.toFloat()
            canvas.drawLine(dp(12).toFloat(), y, (width - dp(12)).toFloat(), y, separator)
        }
    }
}
