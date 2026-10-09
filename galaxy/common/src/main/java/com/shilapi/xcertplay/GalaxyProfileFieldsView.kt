package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.Context
import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.AudioOutputPolicy

/** 分类表单只写草稿；选择器中的确认不等于保存整份文件。 */
internal object GalaxyProfileFieldsView {
    val groups = listOf(R.string.l7_template_model, R.string.l7_section_projection, R.string.l7_template_title,
        R.string.l7_section_app_ui, R.string.l7_section_floating, R.string.automatic_connection,
        R.string.l7_start_wireless, R.string.l7_auth_title, R.string.l7_logs_title)
    fun add(context: GalaxyConfigurationContext, parent: LinearLayout, group: Int, keys: Set<String>? = null) {
        if (group == R.string.l7_template_model) {
            val models = L7AudioTemplates.Model.entries
            L7Components.choice(parent, context.getString(group), models.map { L7AudioModelConfirmation.name(context, it) },
                L7AudioTemplates.model(context).ordinal) { L7AudioTemplates.selectModel(context, models[it]) }
        }
        if (group == R.string.l7_template_title) {
            L7AudioSettings.page(context, parent) { title, current, _, apply ->
                L7Components.select(context, title, AudioOutputPolicy.choices.map { L7AudioSettings.label(context, it) },
                    AudioOutputPolicy.choices.indexOf(current), context.getString(R.string.profile_update_draft)) {
                    apply(AudioOutputPolicy.choices[it])
                }
            }
        }
        if (group == R.string.l7_section_projection && keys == null) GalaxyVideoDecoderSettings.add(context, parent)
        GalaxyConfigurationFields.fields.filter { it.group == group && it.key != "display_scale_tenths" &&
            (keys == null || it.key in keys) }.forEach { field ->
            val prefs = context.getSharedPreferences(field.space, 0)
            fun value(): Any? = prefs.all[field.key] ?: field.default
            val title = context.getString(field.title)
            if (field.default is Boolean) {
                parent.addView(L7Components.switchRow(context, title, "", value() as Boolean) { selected ->
                    prefs.edit().putBoolean(field.key, selected).commit()
                })
            } else {
                lateinit var row: L7SettingRow
                fun label(): String {
                    val current = value()
                    if (field.sensitive) return context.getString(if (current.toString().isEmpty()) R.string.profile_unset else R.string.profile_set)
                    val index = field.choices.indexOf(current)
                    return if (index >= 0 && index < field.labels.size) context.getString(field.labels[index]) else
                        current.toString().ifEmpty { context.getString(R.string.profile_unset) }
                }
                row = L7Components.valueRow(context, title, label()) {
                    if (field.choices.isNotEmpty()) {
                        val labels = field.choices.mapIndexed { index, option ->
                            field.labels.getOrNull(index)?.let(context::getString) ?: option.toString()
                        }
                        L7Components.select(context, title, labels, field.choices.indexOf(value()),
                            context.getString(R.string.profile_update_draft)) { selected ->
                            val edit = prefs.edit()
                            GalaxyProfiles.put(edit, field.key, field.choices[selected]); edit.commit()
                            row.setValue(label())
                        }
                    } else edit(context, title, field, value()) { selected ->
                        val edit = prefs.edit()
                        GalaxyProfiles.put(edit, field.key, selected); edit.commit()
                        row.setValue(label())
                    }
                }
                parent.addView(row)
            }
        }
    }
    private fun edit(context: Context, title: String, field: GalaxyConfigurationFields.Field,
        value: Any?, changed: (Any) -> Unit) {
        val input = EditText(context).apply {
            setText(value?.toString().orEmpty())
            inputType = if (field.default is Int) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT or
                if (field.sensitive) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(if (field.default is Int) 8 else 2048))
            setTextColor(context.getColor(R.color.product_ui_text)); maxLines = 3
        }
        val dialog = L7Dialogs.builder(context).setTitle(title).setMessage(R.string.config_page_value_hint).setView(input)
            .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.profile_update_draft, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = input.text.toString()
                val number = text.toIntOrNull()
                if (field.default is Int && (number == null || (field.minimum != null && number !in field.minimum..field.maximum!!))) {
                    input.error = context.getString(R.string.profile_invalid_value); return@setOnClickListener
                }
                changed(if (field.default is Int) number!! else text)
                dialog.dismiss()
            }
        }
        dialog.show()
    }
}
