package com.shilapi.xcertplay

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import com.shilapi.xcertplay.host.R

/** 设置双栏使用同一组渐变和卡片色阶；只绘制静态背景，不增加动画或模糊开销。 */
internal object L7SettingsStyle {
    fun content(view: View) = L7Ui.bind(view) {
        view.background = gradient(view, R.color.l7_settings_content_top, R.color.l7_settings_content_bottom)
    }

    fun navigation(view: View) {
        view.elevation = 0f
        view.clipToOutline = false
        L7Ui.bind(view) {
            val separator = GradientDrawable().apply { setColor(view.context.getColor(R.color.l7_settings_divider)) }
            view.background = LayerDrawable(arrayOf(
                navigationBackground(view.context), separator,
            )).apply {
                setLayerWidth(1, L7Components.dp(view.context, 1).coerceAtLeast(1))
                setLayerGravity(1, Gravity.END or Gravity.FILL_VERTICAL)
            }
        }
    }

    fun navigationBackground(context: Context, radius: Int = 0, outlined: Boolean = false) = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(context.getColor(R.color.l7_settings_navigation_top), context.getColor(R.color.l7_settings_navigation_bottom)),
    ).apply {
        cornerRadius = L7Components.dp(context, radius).toFloat()
        if (outlined) setStroke(L7Components.dp(context, 1).coerceAtLeast(1), context.getColor(R.color.l7_settings_divider))
    }

    fun card(view: View) = L7Ui.bind(view) {
        view.background = L7Ui.rounded(view.context, view.context.getColor(R.color.l7_settings_card), 4).apply {
            setStroke(L7Components.dp(view.context, 1).coerceAtLeast(1), view.context.getColor(R.color.l7_settings_card_border))
        }
    }

    private fun gradient(view: View, top: Int, bottom: Int) = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(view.context.getColor(top), view.context.getColor(bottom)),
    )
}
