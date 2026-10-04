package com.shilapi.xcertplay

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R

/** 首页快捷操作复用日志查看回调与唯一上传任务；进入页面只刷新状态。 */
internal class L7QuickLogActions(private val context: Context, parent: LinearLayout, onLogs: () -> Unit, onOptions: () -> Unit) {
    private val deviceId = RemoteLogDevice.id(context)
    private val upload = L7Components.actionButton(context, context.getString(R.string.l7_log_upload), primary = true) {
        RemoteLogUpload.start(context); update()
    }
    private val retry = L7Components.actionButton(context, context.getString(R.string.l7_log_retry)) {
        RemoteLogUpload.start(context, retry = true); update()
    }
    private val cancel = L7Components.actionButton(context, context.getString(R.string.l7_log_cancel)) {
        RemoteLogUpload.cancel(); update()
    }
    private val options = L7Components.actionButton(context, context.getString(R.string.l7_log_server), click = onOptions)
    private val status = L7Typography.text(context, "", L7Typography.Role.FEEDBACK).apply {
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    init {
        L7SettingsSection.actions(parent, context.getString(R.string.l7_log_quick_hint)) { group ->
            listOf(
                L7Components.actionButton(context, context.getString(R.string.l7_log_view_short), click = onLogs),
                upload,
                L7Components.actionButton(context, context.getString(R.string.l7_log_clear_logs)) { confirmClear(true) },
                L7Components.actionButton(context, context.getString(R.string.l7_log_clear_reports)) { confirmClear(false) },
            ).forEachIndexed { index, button ->
                group.addView(button, LinearLayout.LayoutParams(-1, -2).apply { if (index > 0) topMargin = L7Components.dp(context, 12) })
            }
            listOf(status, retry, cancel, options).forEach { view ->
                group.addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = L7Components.dp(context, 12) })
            }
        }
        update()
    }

    private fun confirmClear(logs: Boolean) {
        L7Dialogs.builder(context).setTitle(if (logs) R.string.l7_log_clear_logs else R.string.l7_log_clear_reports)
            .setMessage(if (logs) R.string.l7_log_clear_logs_hint else R.string.l7_log_clear_reports_hint)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.l7_debug_clear) { _, _ ->
                val started = L7ProbeRunner.clear(context, logs) { success ->
                    notice(if (success) R.string.l7_log_cleared else R.string.l7_log_clear_failed)
                }
                if (!started) notice(R.string.l7_log_clear_busy)
            }.show()
    }

    private fun notice(message: Int) {
        val activity = context as? android.app.Activity
        if (activity != null && !activity.isDestroyed) L7Notice.show(activity, context.getString(message))
        else if (activity == null) android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    fun update() {
        val config = RemoteLogConfig.load(context)
        val current = RemoteLogUpload.status
        val busy = current.phase == RemoteLogUpload.Phase.UPLOADING
        val valid = config.valid()
        val name = if (valid) config.forDevice(deviceId).stream else deviceId
        upload.isEnabled = valid && !busy
        retry.visibility = if (valid && current.phase == RemoteLogUpload.Phase.FAILED) View.VISIBLE else View.GONE
        cancel.visibility = if (busy) View.VISIBLE else View.GONE
        options.visibility = if (valid) View.GONE else View.VISIBLE
        val text = if (valid) L7RemoteLogSettings.statusText(context, current, name) else context.getString(R.string.l7_log_config_first)
        if (status.text.toString() != text) status.text = text
    }
}
