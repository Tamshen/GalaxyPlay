package com.shilapi.xcertplay

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ImageView
import android.widget.ScrollView
import com.shilapi.xcertplay.host.R

/** 连接等待页复用统一组件；宿主只接收操作和更新真实连接阶段。 */
internal class L7ConnectionPanel(
    context: Context,
    wireless: Boolean,
    onReturn: () -> Unit,
    onCancel: () -> Unit,
    onRecovery: () -> Unit,
) : ScrollView(context) {
    private fun dp(value: Int) = L7Components.dp(context, value)

    val stage = L7Components.text(context, context.getString(R.string.getting_carplay_ready)).apply {
        textSize = 20f
        gravity = Gravity.CENTER
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    val recoveryButton = L7Components.actionButton(context, context.getString(R.string.reset_carplay_wi_fi), click = onRecovery)
        .apply { visibility = View.GONE }
    private val cancelButton = L7Components.actionButton(context, context.getString(R.string.l7_cancel_connection)) {
        cancelConnection(onCancel)
    }

    init {
        isFillViewport = true
        L7Ui.bind(this) { setBackgroundColor(context.getColor(R.color.product_ui_background)) }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        val card = BoundedContent(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(24), 0, dp(24))
        }
        card.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_carplay)
            contentDescription = context.getString(R.string.carplay_icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(dp(80), dp(80)).apply { bottomMargin = dp(24) })
        card.addView(L7Components.text(context, context.getString(R.string.l7_entry_title)).apply {
            textSize = 36f; gravity = Gravity.CENTER
        }, row())
        card.addView(L7Components.text(context, context.getString(
            if (wireless) R.string.l7_start_wireless else R.string.connect_with_usb
        )).apply { textSize = 24f; gravity = Gravity.CENTER }, row(top = 16))
        card.addView(ProgressBar(context).apply {
            isIndeterminate = true
            L7Ui.bind(this) { indeterminateTintList = ColorStateList.valueOf(context.getColor(R.color.product_ui_accent)) }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(32), dp(32)).apply { topMargin = dp(24); bottomMargin = dp(12) })
        card.addView(stage, row())
        card.addView(L7Components.text(context, context.getString(
            if (wireless) R.string.keep_your_iphone_nearby_with_bluetooth_and_wi_fi_on_allow
            else R.string.use_a_usb_data_cable_and_unlock_your_iphone_allow_trust_an
        ), secondary = true).apply { textSize = 18f; gravity = Gravity.CENTER }, row(top = 16))
        card.addView(recoveryButton, row(top = 24))
        card.addView(L7Components.actionButton(context, context.getString(R.string.back_to_diplay),
            primary = true, click = onReturn), row(top = 24))
        card.addView(cancelButton, row(top = 12))
        card.addView(L7Components.text(context, context.getString(R.string.l7_connection_return_hint),
            secondary = true).apply { gravity = Gravity.CENTER }, row(top = 20))
        content.addView(card, LinearLayout.LayoutParams(-1, -2))
        addView(content)
    }

    private fun row(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }

    private fun cancelConnection(onCancel: () -> Unit) {
        // 先禁用并由统一组件刷新反馈，防止异步关闭期间重复取消。
        cancelButton.isEnabled = false
        onCancel()
    }

    private class BoundedContent(context: Context) : LinearLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val maximum = L7Components.dp(context, 560)
            val width = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) maximum
                else MeasureSpec.getSize(widthMeasureSpec).coerceAtMost(maximum)
            super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightMeasureSpec)
        }
    }
}
