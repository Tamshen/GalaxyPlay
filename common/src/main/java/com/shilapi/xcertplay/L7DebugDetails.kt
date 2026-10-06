package com.shilapi.xcertplay

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R

/** 技术证据默认收起；展开仅改变展示，日志仍由各模块默认写入。 */
internal class L7DebugDetails(context: Context, parent: LinearLayout) {
    private val body: L7SettingRow
    private var expanded = false
    init {
        lateinit var row: L7SettingRow
        lateinit var toggle: L7SettingRow
        L7SettingsSection.add(parent) { card ->
            toggle = L7Components.actionRow(context, context.getString(R.string.l7_debug_details)) {
                expanded = !expanded
                body.visibility = if (expanded) View.VISIBLE else View.GONE
                toggle.titleView.text = context.getString(if (expanded) R.string.l7_debug_details_hide else R.string.l7_debug_details)
            }.also(card::addView)
            row = L7SettingRow(context, context.getString(R.string.l7_debug_details)).apply { visibility = View.GONE }
            card.addView(row)
        }
        body = row
    }
    fun update(value: String) { body.setValue(value.takeLast(6000)) }
}
