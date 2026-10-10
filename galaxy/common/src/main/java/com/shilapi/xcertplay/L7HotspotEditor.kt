package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.ManualHotspotValidation

/** 日常热点只收名称和密码；候选留在窗口内，校验成功且确认后才交给调用者。 */
internal object L7HotspotEditor {
    fun show(context: Context, name: String, password: String, applyLabel: Int = R.string.save_details,
             apply: (String, String) -> Unit): AlertDialog {
        val fields = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        fun input(title: Int, value: String, secret: Boolean): EditText {
            fields.addView(L7Typography.text(context, context.getString(title), L7Typography.Role.LABEL))
            return EditText(context).apply {
                hint = context.getString(title)
                inputType = InputType.TYPE_CLASS_TEXT or if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD
                    else InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                isSaveEnabled = false
                setSingleLine()
                // 单行设置会替换密码变换，最后显式恢复遮罩，与关闭的显示开关保持一致。
                if (secret) transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
                setText(value)
                fields.addView(this, LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = L7Components.dp(context, 8); bottomMargin = L7Components.dp(context, 16)
                })
            }
        }
        val ssid = input(R.string.hotspot_name, name, false)
        val passphrase = input(R.string.hotspot_password, password, true)
        ssid.imeOptions = EditorInfo.IME_ACTION_NEXT or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        passphrase.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        fields.addView(L7Components.switchRow(context, context.getString(R.string.show_password), "", false) {
            passphrase.transformationMethod = if (it) null else android.text.method.PasswordTransformationMethod.getInstance()
            passphrase.setSelection(passphrase.text.length)
        })
        val feedback = L7Typography.text(context, "", L7Typography.Role.FEEDBACK)
        fields.addView(feedback)
        fun hideKeyboard() {
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(passphrase.windowToken ?: ssid.windowToken, 0)
        }
        ssid.setOnEditorActionListener { _, action, _ ->
            (action == EditorInfo.IME_ACTION_NEXT).also { if (it) passphrase.requestFocus() }
        }
        passphrase.setOnEditorActionListener { _, action, _ ->
            (action == EditorInfo.IME_ACTION_DONE).also { if (it) hideKeyboard() }
        }
        var submitted = false
        val hint = context.getString(R.string.config_hotspot_hint) +
            if (applyLabel == R.string.profile_update_draft) "\n" + context.getString(R.string.config_page_value_hint) else ""
        val dialog = L7Dialogs.builder(context).setTitle(R.string.car_hotspot_details)
            .setMessage(hint).setView(fields)
            .setNegativeButton(R.string.cancel, null).setPositiveButton(applyLabel, null).create()
        dialog.setOnDismissListener { hideKeyboard(); ssid.text.clear(); passphrase.text.clear() }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (submitted) return@setOnClickListener
                val candidate = ssid.text.toString().trim()
                val secret = passphrase.text.toString()
                val problem = ManualHotspotValidation.error(candidate, secret)
                if (problem != null) {
                    feedback.text = context.getString(problem.messageResource())
                    L7Ui.text(feedback, R.color.product_ui_danger)
                    feedback.accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
                } else {
                    submitted = true
                    dialog.dismiss()
                    apply(candidate, secret)
                }
            }
        }
        dialog.show()
        return dialog
    }
}
