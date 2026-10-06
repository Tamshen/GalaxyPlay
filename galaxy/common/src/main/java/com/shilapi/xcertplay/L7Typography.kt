package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Typeface
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import kotlin.math.roundToInt

/** 原生文字角色与 UI 组件规范 0.2 保持一致；行高随系统字体缩放。 */
internal object L7Typography {
    enum class Role(val size: Int, val line: Int, val medium: Boolean = false, val secondary: Boolean = true) {
        PAGE_TITLE(24, 32, true, false),
        SECTION_TITLE(18, 26, true),
        LABEL(20, 28, false, false),
        VALUE(18, 26),
        DESCRIPTION(16, 24),
        FEEDBACK(16, 24),
    }

    fun text(context: Context, value: String, role: Role) = TextView(context).apply {
        text = value
        textSize = role.size.toFloat()
        typeface = Typeface.create(if (role.medium) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        includeFontPadding = false
        setLineHeight((role.line * resources.displayMetrics.scaledDensity).roundToInt())
        L7Ui.text(this, if (role.secondary) R.color.product_ui_muted else R.color.product_ui_text)
    }
}
