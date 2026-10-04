package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import com.shilapi.xcertplay.host.R

/** 远程日志仍属于诊断页，配置确认保存，上传只由明确按钮触发。 */
internal class L7RemoteLogSettings(private val context: Context, parent: LinearLayout) {
    private val deviceId = RemoteLogDevice.id(context)
    private var logName = deviceId
    private val device = L7Components.actionRow(context, context.getString(R.string.l7_log_device),
        context.getString(R.string.l7_log_device_hint)) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
            ClipData.newPlainText(context.getString(R.string.l7_log_device), logName))
        Toast.makeText(context, R.string.l7_log_device_copied, Toast.LENGTH_SHORT).show()
    }.apply { setValue(deviceId) }
    private val server = L7Components.actionRow(context, context.getString(R.string.l7_log_server),
        context.getString(R.string.l7_log_server_hint)) { configure() }
    private val upload = L7Components.actionRow(context, context.getString(R.string.l7_log_upload)) {
        RemoteLogUpload.start(context)
        update()
    }
    private val retry = L7Components.actionRow(context, context.getString(R.string.l7_log_retry)) {
        RemoteLogUpload.start(context, retry = true)
        update()
    }
    private val cancel = L7Components.actionRow(context, context.getString(R.string.l7_log_cancel)) {
        RemoteLogUpload.cancel()
        update()
    }
    private val state = L7SettingRow(context, context.getString(R.string.l7_log_state), "")

    init {
        L7SettingsSection.add(parent, context.getString(R.string.l7_log_remote),
            footer = context.getString(R.string.l7_log_manual_hint)) { card ->
            card.addView(server)
            card.addView(device)
            card.addView(state)
            card.addView(upload)
            card.addView(retry)
            card.addView(cancel)
        }
        update()
    }

    fun update() {
        val config = RemoteLogConfig.load(context)
        val status = RemoteLogUpload.status
        val busy = status.phase == RemoteLogUpload.Phase.UPLOADING
        val target = if (RemoteLogConfig.validEndpoint(config.endpoint)) config.forDevice(deviceId) else null
        logName = target?.stream ?: deviceId
        device.setValue(logName)
        server.setValue(target?.endpoint ?: config.endpoint.ifEmpty { context.getString(R.string.l7_log_unconfigured) })
        upload.isEnabled = config.valid() && !busy
        upload.setFeedback(if (config.valid()) "" else context.getString(R.string.l7_log_config_first))
        retry.isEnabled = config.valid() && status.phase == RemoteLogUpload.Phase.FAILED
        cancel.isEnabled = busy
        state.setValue(when (status.phase) {
            RemoteLogUpload.Phase.IDLE -> context.getString(R.string.l7_log_idle)
            RemoteLogUpload.Phase.UPLOADING -> context.getString(R.string.l7_log_uploading)
            RemoteLogUpload.Phase.SUCCESS -> context.getString(R.string.l7_log_success, logName, status.id.take(8))
            RemoteLogUpload.Phase.CANCELLED -> context.getString(R.string.l7_log_cancelled)
            RemoteLogUpload.Phase.FAILED -> when (status.code) {
                0 -> context.getString(R.string.l7_log_network_failed)
                -1 -> context.getString(R.string.l7_log_partial)
                else -> context.getString(R.string.l7_log_http_failed, status.code)
            }
        })
    }

    private fun configure() {
        val config = RemoteLogConfig.load(context)
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        fun field(label: Int, value: String, password: Boolean, limit: Int): EditText {
            body.addView(L7Components.text(context, context.getString(label), secondary = true))
            return EditText(context).apply {
                setSingleLine(true)
                inputType = InputType.TYPE_CLASS_TEXT or if (password) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_URI
                if (password) transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
                isSaveEnabled = false
                importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
                filters = arrayOf(InputFilter.LengthFilter(limit))
                setText(value)
                hint = context.getString(label)
                body.addView(this, LinearLayout.LayoutParams(-1, -2))
            }
        }
        val url = field(R.string.l7_log_url_label, config.endpoint, false, 2048)
        val token = field(R.string.l7_log_token_label, config.authorization, true, 4096)
        val dialog = L7Dialogs.builder(context).setTitle(R.string.l7_log_server)
            .setMessage(R.string.l7_log_config_hint).setView(body)
            .setNeutralButton(R.string.l7_log_reset) { _, _ ->
                if (RemoteLogConfig.reset(context)) { RemoteLogUpload.cancel(); update() }
                else Toast.makeText(context, R.string.l7_log_save_failed, Toast.LENGTH_LONG).show()
            }
            .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.save, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val next = RemoteLogConfig(url.text.toString().trim(), token.text.toString().trim())
                when {
                    !RemoteLogConfig.validEndpoint(next.endpoint) -> url.error = context.getString(R.string.l7_log_url_error)
                    !next.valid() -> token.error = context.getString(R.string.l7_log_token_error)
                    !RemoteLogConfig.save(context, next) -> token.error = context.getString(R.string.l7_log_save_failed)
                    else -> { RemoteLogUpload.cancel(); dialog.dismiss(); update() }
                }
            }
        }
        dialog.show()
    }
}
