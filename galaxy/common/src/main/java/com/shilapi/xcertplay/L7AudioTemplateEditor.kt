package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.text.InputFilter
import android.widget.EditText
import android.widget.Toast
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.AudioRoutingTemplate

/** 编辑的是草稿；校验或保存失败时保留原文件和编辑内容。 */
internal object L7AudioTemplateEditor {
    fun show(context: Context, changed: () -> Unit) {
        val model = L7AudioTemplates.model(context)
        val editor = EditText(context).apply {
            setText(L7AudioTemplates.load(context).toJson())
            typeface = Typeface.MONOSPACE
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            filters = arrayOf(InputFilter.LengthFilter(AudioRoutingTemplate.MAX_BYTES))
            minLines = 8
            maxLines = 14
            setTextColor(context.getColor(R.color.product_ui_text))
        }
        val dialog = L7Dialogs.builder(context).setTitle(R.string.l7_template_edit)
            .setMessage(R.string.l7_template_schema_hint).setView(editor)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(if (context is GalaxyConfigurationContext && context.editable)
                R.string.profile_update_draft else R.string.l7_save_next_connection, null).create()
        var committed = false
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (committed) return@setOnClickListener
                if (model != L7AudioTemplates.model(context)) {
                    Toast.makeText(context, R.string.l7_template_model_changed, Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val template = runCatching { AudioRoutingTemplate.parse(editor.text.toString()) }.getOrNull()
                if (template == null) {
                    editor.error = context.getString(R.string.l7_template_invalid)
                    return@setOnClickListener
                }
                if (runCatching { L7AudioTemplates.saveCustom(context, template, model) }.isFailure) {
                    Toast.makeText(context, R.string.l7_template_save_failed, Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                committed = true
                dialog.dismiss()
                changed()
            }
            L7Components.styleDialog(dialog)
        }
        dialog.show()
    }
}
