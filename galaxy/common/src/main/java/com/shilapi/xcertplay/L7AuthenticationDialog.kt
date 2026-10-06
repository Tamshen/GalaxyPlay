// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.mfi.LocalMfiIdentityStore

/** 只在用户粘贴并确认时读取材料，不写入日志或界面状态备份。 */
internal class L7AuthenticationDialog(
    private val activity: Activity,
    private val onChanged: (reconnect: Boolean) -> Unit,
) {
    fun chooseSource() {
        var selected = L7Authentication.source(activity).ordinal
        val message = TextView(activity).apply {
            text = activity.getString(R.string.l7_auth_switch_hint)
            setPadding(dp(24), dp(12), dp(24), dp(12))
        }
        val dialog = L7Dialogs.builder(activity)
            .setTitle(R.string.l7_auth_title)
            .setSingleChoiceItems(arrayOf(
                activity.getString(R.string.l7_auth_builtin),
                activity.getString(R.string.l7_auth_text),
                activity.getString(R.string.l7_auth_usb),
                activity.getString(R.string.l7_auth_remote),
            ), selected) { _, which -> selected = which }
            .setView(message)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null).create()
        dialog.setOnShowListener {
            if (activity.resources.getBoolean(R.bool.config_l7_product_ui)) L7Components.styleDialog(dialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val source = L7Authentication.Source.entries[selected]
                when {
                    source == L7Authentication.Source.REMOTE -> { dialog.dismiss(); remoteSettings() }
                    source == L7Authentication.Source.USB -> apply(dialog, message, source, false) { null }
                    source == L7Authentication.Source.TEXT && !L7Authentication.hasImported(activity) -> { dialog.dismiss(); quickImport() }
                    else -> apply(dialog, message, source, false) {
                        if (source == L7Authentication.Source.BUILT_IN) L7Authentication.prepareBuiltIn(activity)
                        else L7Authentication.prepareImported(activity)
                    }
                }
            }
        }
        dialog.show()
    }

    private fun remoteSettings() {
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(12))
        }
        val message = TextView(activity).apply { setText(R.string.l7_auth_remote_hint) }
        val server = input(R.string.l7_auth_remote_server_hint).apply {
            minLines = 1; maxLines = 3
            setText(AirPlayPersistence.loadRemoteMfiServer(activity))
        }
        val token = input(R.string.l7_auth_remote_token_hint).apply {
            setSingleLine()
            transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
            setText(AirPlayPersistence.loadRemoteMfiToken(activity))
        }
        body.addView(message)
        body.addView(TextView(activity).apply { setText(R.string.l7_auth_remote_server) })
        body.addView(server)
        body.addView(TextView(activity).apply { setText(R.string.l7_auth_remote_token) })
        body.addView(token)
        val dialog = L7Dialogs.builder(activity).setTitle(R.string.l7_auth_remote)
            .setView(ScrollView(activity).apply { addView(body) })
            .setPositiveButton(R.string.l7_auth_remote_use, null)
            .setNegativeButton(R.string.cancel, null).create()
        dialog.setOnDismissListener { token.text.clear() }
        dialog.setOnShowListener {
            if (activity.resources.getBoolean(R.bool.config_l7_product_ui)) L7Components.styleDialog(dialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val address = server.text.toString().trim()
                val secret = token.text.toString()
                apply(dialog, message, L7Authentication.Source.REMOTE, false, address, secret) {
                    L7Authentication.validateRemote(address, secret)
                    null
                }
            }
        }
        dialog.show()
    }

    fun quickImport() {
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(12))
        }
        val certificate = input(R.string.l7_auth_certificate_hint)
        val key = input(R.string.l7_auth_key_hint)
        val message = TextView(activity).apply { text = activity.getString(R.string.l7_auth_paste_hint) }
        body.addView(message)
        body.addView(TextView(activity).apply { setText(R.string.l7_auth_certificate) })
        body.addView(certificate)
        body.addView(TextView(activity).apply { setText(R.string.l7_auth_private_key) })
        body.addView(key)
        val dialog = L7Dialogs.builder(activity).setTitle(R.string.l7_auth_quick_import)
            .setView(ScrollView(activity).apply { addView(body) })
            .setPositiveButton(R.string.l7_auth_import_use, null)
            .setNegativeButton(R.string.cancel, null).create()
        dialog.setOnDismissListener { key.text.clear(); certificate.text.clear() }
        dialog.setOnShowListener {
            if (activity.resources.getBoolean(R.bool.config_l7_product_ui)) L7Components.styleDialog(dialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                // 输入框只能在主线程读取；后台校验通过后才停止当前会话。
                val keyText = key.text.toString()
                val certificateText = certificate.text.toString()
                apply(dialog, message, L7Authentication.Source.TEXT, true) {
                    LocalMfiIdentityStore.prepareText(keyText, certificateText)
                }
            }
        }
        dialog.show()
    }

    private fun apply(
        dialog: AlertDialog,
        message: TextView,
        source: L7Authentication.Source,
        saveImport: Boolean,
        server: String = "",
        token: String = "",
        prepare: () -> LocalMfiIdentityStore.Identity?,
    ) {
        fun busy(value: Boolean) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = !value
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = !value
            dialog.setCancelable(!value)
            fun inputs(view: View) {
                if (view is EditText) view.isEnabled = !value
                if (view is ViewGroup) for (index in 0 until view.childCount) inputs(view.getChildAt(index))
            }
            dialog.window?.decorView?.let(::inputs)
            dialog.listView?.isEnabled = !value
        }
        fun fail(error: Throwable) {
            if (!activity.isDestroyed && dialog.isShowing) {
                busy(false)
                message.setText(when {
                    error is L7Authentication.MissingBuiltIn -> R.string.l7_auth_builtin_missing
                    source == L7Authentication.Source.REMOTE -> R.string.l7_auth_remote_failed
                    else -> R.string.l7_auth_import_failed
                })
                if (activity.resources.getBoolean(R.bool.config_l7_product_ui)) L7Ui.text(message, R.color.product_ui_danger)
            }
        }
        busy(true)
        if (activity.resources.getBoolean(R.bool.config_l7_product_ui)) L7Ui.text(message, R.color.product_ui_muted)
        message.setText(R.string.l7_auth_validating)
        Thread({
            val result = runCatching(prepare)
            activity.runOnUiThread {
                val identity = result.getOrNull()
                if (result.isFailure) { fail(result.exceptionOrNull()!!); return@runOnUiThread }
                if (activity.isDestroyed || !dialog.isShowing) { identity?.close(); return@runOnUiThread }
                val reconnect = CarPlayBackgroundSession.hasSession()
                CarPlayBackgroundSession.stop {
                    Thread({
                        val saved = runCatching {
                            if (identity != null) identity.use { L7Authentication.install(activity.applicationContext, source, it, saveImport) }
                            else L7Authentication.selectExternal(activity.applicationContext, source, server, token)
                        }
                        activity.runOnUiThread {
                            if (saved.isFailure) fail(saved.exceptionOrNull()!!)
                            else if (!activity.isDestroyed) {
                                dialog.dismiss()
                                onChanged(reconnect)
                            }
                        }
                    }, "l7-auth-save").start()
                }
            }
        }, "l7-auth-validate").start()
    }

    private fun input(hint: Int) = EditText(activity).apply {
        setHint(hint)
        typeface = Typeface.MONOSPACE
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        minLines = 4; maxLines = 8
        filters = arrayOf(InputFilter.LengthFilter(LocalMfiIdentityStore.MAX_TEXT_LENGTH))
        isSaveEnabled = false
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
}
