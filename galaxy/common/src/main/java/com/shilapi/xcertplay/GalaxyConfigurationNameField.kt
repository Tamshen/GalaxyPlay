package com.shilapi.xcertplay

import android.content.Context
import android.app.AlertDialog
import android.text.InputFilter
import android.widget.EditText
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R

/** 名称是配置元数据；修改进入同一草稿，取消不影响文件和参数。 */
internal object GalaxyConfigurationNameField {
    fun add(context: Context, parent: LinearLayout, name: String, changed: (String) -> Unit) {
        L7SettingsSection.add(parent) { card ->
            card.addView(L7Components.valueRow(context, context.getString(R.string.config_profile_name), name) {
                val input = EditText(context).apply {
                    L7Ui.input(this); setText(name); filters = arrayOf(InputFilter.LengthFilter(40)); setSingleLine()
                }
                val dialog = L7Dialogs.builder(context).setTitle(R.string.config_profile_name).setView(input)
                    .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.profile_update_draft, null).create()
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val value = input.text.toString().trim()
                        if (value.isEmpty() || value.any { it.code < 32 }) input.error = context.getString(R.string.profile_invalid_value)
                        else { dialog.dismiss(); changed(value) }
                    }
                }
                dialog.show()
            })
        }
    }
}
