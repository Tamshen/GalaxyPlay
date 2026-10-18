package com.shilapi.xcertplay

import android.content.Context
import android.app.AlertDialog
import android.text.InputFilter
import android.widget.EditText
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R

/** 配置名称只读展示，保存时才命名；取消保留草稿，确认成功后关闭。 */
internal object GalaxyConfigurationNameField {
    fun add(context: Context, parent: LinearLayout, name: String) {
        L7SettingsSection.add(parent) { card ->
            card.addView(L7SettingRow(context, context.getString(R.string.config_profile_name)).apply {
                setValue(GalaxyConfigurationNames.display(context, name))
            })
        }
    }
    fun save(context: Context, name: String, creating: Boolean = false,
             failure: () -> Int = { R.string.profile_save_failed }, commit: (String) -> Boolean): AlertDialog {
        val input = EditText(context).apply {
            L7Ui.input(this); setText(name); filters = arrayOf(InputFilter.LengthFilter(40)); setSingleLine()
        }
        val dialog = L7Dialogs.builder(context).setTitle(if (creating) R.string.config_file_create else R.string.config_save_title)
            .setMessage(if (creating) R.string.config_file_new_hint else R.string.config_save_name_hint).setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(if (creating) R.string.config_file_create else R.string.save, null).create()
        var applied = false
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (applied) return@setOnClickListener
                val value = input.text.toString().trim()
                if (value.isEmpty() || value.any { it.code < 32 }) input.error = context.getString(R.string.profile_invalid_value)
                else if (commit(value)) { applied = true; dialog.dismiss() }
                else input.error = context.getString(failure())
            }
        }
        dialog.show()
        return dialog
    }
}
