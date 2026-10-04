package com.shilapi.xcertplay

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.NativeHotspotCredentials

/** 显式操作始终有进度与持久结果；自动读取只更新页面，不弹窗打断用户。 */
internal class L7HotspotActions(private val activity: Activity, private val task: L7HotspotTask) {
    private var window: L7TaskProgressDialog? = null
    val showing get() = window != null

    fun read() = run(reading = true) { task.read() }
    fun start(value: NativeHotspotCredentials? = null) = run(reading = false, applying = value != null) { task.start(value) }

    private fun run(reading: Boolean, applying: Boolean = false, begin: () -> Boolean) {
        if (window != null || !L7Agreement.require(activity)) return
        if (!begin()) {
            L7Dialogs.builder(activity).setTitle(R.string.l7_hotspot_not_started)
                .setMessage(if (task.status.busy) R.string.l7_hotspot_busy else task.status.message)
                .setPositiveButton(R.string.close, null).show()
            return
        }
        window = L7TaskProgressDialog(activity,
            if (reading) R.string.l7_hotspot_read else if (applying) R.string.l7_hotspot_apply_start else R.string.l7_hotspot_start,
            R.string.l7_hotspot_cancel, R.string.l7_hotspot_cancelled,
            snapshot = { L7HotspotFeedback.state(activity, task.status, reading, applying) },
            onStop = { task.cancel() }, onResult = { L7HotspotFeedback.recover(activity, task.status.message) },
            onDismiss = { window = null }).also { it.show() }
    }

    fun resume() { window?.resume() }
    fun background() { window?.stop(); window?.pause() }
    fun close() { window?.stop(); window?.dismiss(); window = null }
}

/** 开启与写入是两件事；标题不能把配置已保存、请求已提交误报成热点已开启。 */
internal object L7HotspotFeedback {
    fun state(activity: Activity, status: L7HotspotTask.Status, reading: Boolean = false, applying: Boolean = false): L7TaskProgress {
        val code = status.message
        val success = code in setOf(R.string.l7_hotspot_ready, R.string.l7_hotspot_ready_no_details, R.string.l7_hotspot_read_ok)
        val cancelled = code == R.string.l7_hotspot_cancelled
        val title = when {
            status.busy -> code
            cancelled -> R.string.l7_hotspot_stopped_title
            applying && code == R.string.l7_hotspot_already_on -> R.string.l7_hotspot_not_applied
            success -> if (reading) R.string.l7_hotspot_read_done else R.string.l7_hotspot_started_title
            reading -> R.string.l7_hotspot_read_failed_title
            status.configurationApplied -> R.string.l7_hotspot_saved_not_started
            else -> R.string.l7_hotspot_not_started
        }
        val recover = !status.busy && !success && !cancelled && code != R.string.l7_hotspot_session_active
        val detail = if (status.busy) activity.getString(R.string.l7_hotspot_wait_hint) else buildList {
            add(activity.getString(code))
            if (status.configurationApplied && !success && !cancelled) add(activity.getString(R.string.l7_hotspot_saved_hint))
            if (!success && !cancelled) add(activity.getString(R.string.l7_hotspot_settings_hint))
        }.joinToString("\n\n")
        return L7TaskProgress(status.busy, activity.getString(title), detail,
            result = recover, error = !status.busy && !success && !cancelled,
            resultLabel = if (code == R.string.l7_hotspot_start_permission) R.string.l7_hotspot_grant else R.string.open_car_hotspot_settings,
            showProgress = status.busy)
    }

    fun recover(activity: Activity, code: Int) {
        if (code == R.string.l7_hotspot_start_permission) {
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${activity.packageName}"))
            if (runCatching { activity.startActivity(intent) }.isSuccess) return
        }
        L7HotspotSettings.openSettings(activity)
    }
}
