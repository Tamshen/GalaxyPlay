package com.shilapi.xcertplay

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R

/** 品牌信息复用设置卡片；窄窗口和大字号时将版本移至右下方，内容自然增高。 */
internal object L7ProductInformationCard {
    fun add(card: L7SettingsCard, version: String) {
        val context = card.context
        val inset = L7Components.dp(context, 24)
        card.setPadding(inset, inset, inset, inset)
        L7Ui.bind(card) {
            card.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(
                context.getColor(R.color.l7_product_card_start), context.getColor(R.color.l7_product_card_end),
            )).apply {
                cornerRadius = L7Components.dp(context, 8).toFloat()
                setStroke(L7Components.dp(context, 1).coerceAtLeast(1), context.getColor(R.color.l7_product_card_border))
            }
        }
        card.addView(ProductIdentity(context, version))
    }
}

private class ProductIdentity(context: Context, version: String) : LinearLayout(context) {
    private val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private val footer = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private val versionLabel = L7Typography.text(context,
        context.getString(R.string.l7_product_version, version), L7Typography.Role.VALUE).apply {
        contentDescription = "${context.getString(R.string.l7_app_version)} $version"
        L7Ui.text(this, R.color.l7_product_card_version)
    }
    private var compact: Boolean? = null

    init {
        orientation = VERTICAL
        layoutParams = LayoutParams(-1, -2)
        header.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_carplay)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LayoutParams(dp(64), dp(64)).apply { marginEnd = dp(20) })
        header.addView(LinearLayout(context).apply {
            orientation = VERTICAL
            addView(L7Typography.text(context, context.getString(R.string.diplay), L7Typography.Role.PAGE_TITLE))
            addView(L7Typography.text(context, context.getString(R.string.l7_product_receiver), L7Typography.Role.DESCRIPTION),
                LayoutParams(-1, -2).apply { topMargin = dp(4) })
        }, LayoutParams(0, -2, 1f))
        addView(header, LayoutParams(-1, -2))
        footer.addView(L7Typography.text(context, context.getString(R.string.l7_product_connections),
            L7Typography.Role.DESCRIPTION).apply { L7Ui.text(this, R.color.l7_product_card_detail) },
            LayoutParams(0, -2, 1f))
        addView(footer, LayoutParams(-1, -2).apply { topMargin = dp(20) })
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 按实际文字缩放和卡片宽度判断，避免版本挤压品牌名称与说明。
        val narrow = MeasureSpec.getSize(widthMeasureSpec) < dp(480) * resources.configuration.fontScale
        if (compact != narrow) {
            (versionLabel.parent as? LinearLayout)?.removeView(versionLabel)
            (if (narrow) footer else header).addView(versionLabel, LayoutParams(-2, -2).apply {
                marginStart = dp(16)
                gravity = if (narrow) Gravity.CENTER_VERTICAL else Gravity.TOP
                topMargin = if (narrow) 0 else dp(3)
            })
            compact = narrow
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun dp(value: Int) = L7Components.dp(context, value)
}
