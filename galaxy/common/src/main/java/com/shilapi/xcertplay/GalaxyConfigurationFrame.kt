package com.shilapi.xcertplay

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import com.shilapi.xcertplay.host.R

/** 快速模板与分类固定在上方，参数独立滚动，主要操作始终可达。 */
internal class GalaxyConfigurationFrame(context: Context, chooseTemplate: () -> Unit,
    changeGroup: (Int) -> Unit, reset: () -> Unit, commit: () -> Unit) : LinearLayout(context) {
    val vehicleButton = L7Components.actionButton(context, context.getString(R.string.template_choose), click = chooseTemplate)
    val tabs = GalaxyConfigurationTabs(context, changeGroup)
    val status = L7Typography.text(context, "", L7Typography.Role.FEEDBACK)
    val fields = LinearLayout(context).apply { orientation = VERTICAL }
    val scroll = ScrollView(context).apply { isFillViewport = false; addView(fields) }
    val resetButton = L7Components.actionButton(context, context.getString(R.string.config_page_restore), click = reset)
    val commitButton = L7Components.actionButton(context, context.getString(R.string.save), true, commit)
    private fun dp(value: Int) = L7Components.dp(context, value)
    init {
        orientation = VERTICAL
        setPadding(dp(16), 0, dp(16), dp(16))
        addView(L7Typography.text(context, context.getString(R.string.template_quick), L7Typography.Role.SECTION_TITLE))
        addView(L7Typography.text(context, context.getString(R.string.template_hint), L7Typography.Role.DESCRIPTION),
            LayoutParams(-1, -2).apply { topMargin = dp(4); bottomMargin = dp(12) })
        addView(vehicleButton, LayoutParams(-1, -2))
        addView(status, LayoutParams(-1, -2).apply { topMargin = dp(12); bottomMargin = dp(12) })
        addView(tabs, LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        addView(scroll, LayoutParams(-1, 0, 1f).apply { topMargin = dp(4); bottomMargin = dp(12) })
        addView(LinearLayout(context).apply {
            addView(resetButton, LayoutParams(0, -2, 1f))
            addView(commitButton, LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
        })
    }
    fun category(index: Int) {
        tabs.category(index)
    }
    fun feedback(value: String, error: Boolean = false) {
        status.text = value
        L7Ui.text(status, if (error) R.color.product_ui_danger else R.color.product_ui_muted)
        status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
}
