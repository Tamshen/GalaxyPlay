package com.shilapi.xcertplay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.widget.Button
import android.widget.TextView
import com.shilapi.xcertplay.host.R

/** L7 的静态界面样式；主题更新只重绘控件，不重建视频 Surface 或连接会话。 */
internal object L7Ui {
    private class PaletteBinding(val apply: () -> Unit)

    fun bind(view: View, apply: () -> Unit) {
        view.setTag(R.id.l7_palette_binding, PaletteBinding(apply))
        apply()
    }

    fun refresh(view: View) {
        (view.getTag(R.id.l7_palette_binding) as? PaletteBinding)?.apply?.invoke()
        if (view is ViewGroup) for (index in 0 until view.childCount) refresh(view.getChildAt(index))
    }

    fun text(view: TextView, color: Int = R.color.product_ui_text) {
        bind(view) { view.setTextColor(view.context.getColor(color)) }
    }

    fun surface(view: View, color: Int = R.color.product_ui_surface, radius: Int = 4) {
        bind(view) { view.background = rounded(view.context, view.context.getColor(color), radius) }
    }

    /** 按压只在当前行内扩散；悬停和键盘焦点使用整行底色，不绘制无边界光圈。 */
    fun rowFeedback(view: View) = bind(view) {
        val accent = view.context.getColor(R.color.product_ui_ripple)
        val highlight = view.context.getColor(R.color.product_ui_selected)
        val states = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), ColorDrawable(Color.TRANSPARENT))
            addState(intArrayOf(android.R.attr.state_hovered), ColorDrawable(highlight))
            addState(intArrayOf(android.R.attr.state_focused), ColorDrawable(highlight))
            addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
        }
        val ripple = ColorStateList(arrayOf(
            intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_pressed),
            intArrayOf(android.R.attr.state_hovered), intArrayOf(android.R.attr.state_focused), intArrayOf(),
        ), intArrayOf(Color.TRANSPARENT, accent, Color.TRANSPARENT, Color.TRANSPARENT, accent))
        view.background = RippleDrawable(ripple, states, ColorDrawable(Color.WHITE))
    }

    fun button(view: Button, primary: Boolean = false, radius: Int = 4, compact: Boolean = false,
               surface: Int = R.color.product_ui_control, withIcon: Boolean = true) {
        view.isAllCaps = false
        view.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        view.stateListAnimator = null
        view.backgroundTintList = null
        view.gravity = Gravity.CENTER
        view.includeFontPadding = false
        view.textSize = if (compact) 13f else 20f
        view.minWidth = 0
        view.minimumWidth = 0
        view.minHeight = L7Components.dp(view.context, if (compact) 48 else 72)
        view.minimumHeight = view.minHeight
        bind(view) {
            val context = view.context
            view.backgroundTintList = null
            val foreground = context.getColor(if (primary) R.color.product_ui_primary_text else R.color.product_ui_text)
            view.setTextColor(ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(context.getColor(R.color.product_ui_disabled), foreground)
            ))
            val fill = rounded(context, context.getColor(
                if (primary && view.isEnabled) R.color.product_ui_accent else surface), radius)
            view.background = RippleDrawable(ColorStateList.valueOf(context.getColor(R.color.product_ui_ripple)),
                fill, null)
            // 替换背景后再次设置内边距，防止系统 Button 的 inset 覆盖统一的图标/文字留白。
            val horizontal = L7Components.dp(context, if (compact) 8 else 16)
            val vertical = L7Components.dp(context, if (compact) 0 else 12)
            view.setPaddingRelative(horizontal, vertical, horizontal, vertical)
            // 按当前可用状态直接重绘背景，避免主题切换复用旧 drawable 的状态与 tint。
            view.backgroundTintList = null
            view.invalidate()
            if (withIcon) L7Icons.decorate(view)
            else view.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null)
        }
    }

    /** 输入框采用细圆角填充和明确的焦点边框，避免 Material 默认下划线。 */
    fun input(view: android.widget.EditText) {
        view.textSize = 20f
        view.minimumHeight = L7Components.dp(view.context, 64)
        bind(view) {
            val context = view.context
            view.setTextColor(context.getColor(R.color.product_ui_text))
            view.setHintTextColor(context.getColor(R.color.product_ui_muted))
            view.backgroundTintList = null
            fun outline(focused: Boolean) = rounded(context, context.getColor(R.color.product_ui_control), 4).apply {
                setStroke(L7Components.dp(context, 1).coerceAtLeast(1), context.getColor(
                    if (focused) R.color.product_ui_accent else R.color.product_ui_border))
            }
            view.background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), outline(true))
                addState(intArrayOf(), outline(false))
            }
            val padding = L7Components.dp(context, 16)
            view.setPaddingRelative(padding, padding, padding, padding)
        }
    }

    fun rounded(context: Context, color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius * context.resources.displayMetrics.density
    }
}
