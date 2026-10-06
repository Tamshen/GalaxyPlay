package com.shilapi.xcertplay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.widget.ImageButton
import com.shilapi.xcertplay.host.R

/** 悬浮导航沿用设置左栏的材质；窗口位置与外观分别维护。 */
internal object L7MenuSurface {
    fun background(context: Context, radius: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(context.getColor(R.color.product_ui_surface))
            cornerRadius = L7Components.dp(context, radius).toFloat()
        }
    }

    fun apply(view: View, radius: Int = 16) {
        view.clipToOutline = true
        view.elevation = L7Components.dp(view.context, 8).toFloat()
        view.outlineAmbientShadowColor = 0x33000000
        view.outlineSpotShadowColor = 0x44000000
        L7Ui.bind(view) { view.background = background(view.context, radius) }
    }

    fun navigation(view: View) {
        floatingOutline(view)
        L7Ui.bind(view) { view.background = L7SettingsStyle.navigationBackground(view.context, 16, outlined = true) }
    }

    fun handle(view: ImageButton) {
        val context = view.context
        view.setImageResource(R.drawable.ic_l7_projection)
        val inset = L7Components.dp(context, 14)
        view.setPadding(inset, inset, inset, inset)
        floatingOutline(view)
        L7Ui.bind(view) {
            view.background = RippleDrawable(ColorStateList.valueOf(context.getColor(R.color.product_ui_ripple)),
                L7SettingsStyle.navigationBackground(context, 16, outlined = true), null)
            view.imageTintList = ColorStateList.valueOf(context.getColor(R.color.product_ui_accent))
        }
    }

    private fun floatingOutline(view: View) {
        view.clipToOutline = true
        view.elevation = L7Components.dp(view.context, 4).toFloat()
        view.outlineAmbientShadowColor = 0x22000000
        view.outlineSpotShadowColor = 0x33000000
    }
}
