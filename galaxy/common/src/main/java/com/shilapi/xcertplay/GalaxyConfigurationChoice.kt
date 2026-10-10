package com.shilapi.xcertplay

import android.content.Context
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R

/** 同一参数共用快捷档位和精确输入，取消任一弹窗均不改变草稿。 */
internal object GalaxyConfigurationChoice {
    fun add(context: Context, parent: LinearLayout, key: String, title: Int,
            values: List<Int>, labels: List<Int>) {
        val preferences = context.getSharedPreferences("xcertplay_airplay", 0)
        val field = GalaxyConfigurationFields.fields.find { it.key == key && it.choices.isEmpty() }
        val names = labels.map(context::getString)
        fun current() = preferences.getInt(key, values.first())
        fun value(): String = values.indexOf(current()).let { index ->
            names.getOrNull(index) ?: context.getString(
                if (field != null) R.string.config_percent_value else R.string.config_custom_value, current())
        }
        lateinit var row: L7SettingRow
        fun save(selected: Int) {
            preferences.edit().putInt(key, selected).commit()
            row.setValue(value())
        }
        row = L7Components.valueRow(context, context.getString(title), value()) {
            val options = names + if (field != null) listOf(context.getString(R.string.config_custom_percent)) else emptyList()
            L7Components.select(context, context.getString(title), options, values.indexOf(current()),
                context.getString(R.string.profile_update_draft)) { index ->
                if (index < values.size) save(values[index])
                else if (field != null) GalaxyProfileFieldsView.edit(context, context.getString(field.title), field, current()) {
                    save(it as Int)
                }
            }
        }
        parent.addView(row)
    }
}
