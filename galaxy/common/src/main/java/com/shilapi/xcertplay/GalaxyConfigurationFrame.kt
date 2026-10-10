package com.shilapi.xcertplay

import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import com.shilapi.xcertplay.host.R

/** 快速模板与分类固定在上方，参数独立滚动，主要操作始终可达。 */
internal class GalaxyConfigurationFrame(context: Context, applyTemplate: (String) -> Unit,
    changeGroup: (Int) -> Unit, reset: () -> Unit, commit: () -> Unit) : LinearLayout(context) {
    val l7 = L7Components.actionButton(context, context.getString(R.string.template_l7)) { applyTemplate("l7") }
    val l6 = L7Components.actionButton(context, context.getString(R.string.template_l6)) { applyTemplate("l6") }
    val status = L7Typography.text(context, "", L7Typography.Role.FEEDBACK)
    val fields = LinearLayout(context).apply { orientation = VERTICAL }
    val scroll = ScrollView(context).apply { isFillViewport = false; addView(fields) }
    val resetButton = L7Components.actionButton(context, context.getString(R.string.config_page_restore), click = reset)
    val commitButton = L7Components.actionButton(context, context.getString(R.string.save), true, commit)
    private val categories = mutableListOf<Button>()
    private fun dp(value: Int) = L7Components.dp(context, value)
    init {
        orientation = VERTICAL
        setPadding(dp(16), 0, dp(16), dp(16))
        addView(L7Typography.text(context, context.getString(R.string.template_quick), L7Typography.Role.SECTION_TITLE))
        addView(L7Typography.text(context, context.getString(R.string.template_overwrite), L7Typography.Role.DESCRIPTION),
            LayoutParams(-1, -2).apply { topMargin = dp(4); bottomMargin = dp(12) })
        addView(LinearLayout(context).apply {
            addView(l7, LayoutParams(0, -2, 1f))
            addView(l6, LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
        })
        addView(status, LayoutParams(-1, -2).apply { topMargin = dp(12); bottomMargin = dp(12) })
        val titles = listOf(R.string.config_page_common, R.string.config_page_connection, R.string.config_page_audio,
            R.string.config_page_video, R.string.config_page_more)
        titles.chunked(3).forEachIndexed { rowIndex, rowTitles ->
            addView(LinearLayout(context).apply {
                rowTitles.forEachIndexed { column, title ->
                    val index = rowIndex * 3 + column
                    val button = L7Components.actionButton(context, context.getString(title)) { changeGroup(index) }
                    button.textSize = 18f
                    button.minHeight = dp(64)
                    button.maxLines = 2
                    categories += button
                    addView(button, LayoutParams(0, -2, 1f).apply { if (column > 0) marginStart = dp(8) })
                }
            }, LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        addView(scroll, LayoutParams(-1, 0, 1f).apply { topMargin = dp(4); bottomMargin = dp(12) })
        addView(LinearLayout(context).apply {
            addView(resetButton, LayoutParams(0, -2, 1f))
            addView(commitButton, LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
        })
    }
    fun category(index: Int) {
        categories.forEachIndexed { position, button ->
            button.isSelected = position == index
            L7Ui.button(button, primary = position == index)
            button.textSize = 18f
            button.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
    }
    fun feedback(value: String, error: Boolean = false) {
        status.text = value
        L7Ui.text(status, if (error) R.color.product_ui_danger else R.color.product_ui_muted)
        status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
}
