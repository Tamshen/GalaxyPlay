package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ProgressBar
import com.shilapi.xcertplay.host.R
import java.lang.ref.WeakReference

/** 全量重置必须显式确认；成功由系统结束应用，失败保留可见恢复入口。 */
internal object L7ResetSettings {
    fun add(activity: Activity, parent: LinearLayout) {
        L7SettingsSection.add(parent, activity.getString(R.string.l7_reset_section)) { card ->
            card.addView(L7Components.actionRow(activity, activity.getString(R.string.l7_reset_title),
                activity.getString(R.string.l7_reset_hint)) { confirm(activity) }.apply {
                L7Ui.text(titleView, R.color.product_ui_danger)
            })
        }
    }

    internal fun confirm(activity: Activity, onConfirm: () -> Unit = { start(activity) }): AlertDialog {
        val message = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(L7Components.text(activity, activity.getString(R.string.l7_reset_warning)).apply {
                L7Ui.text(this, R.color.product_ui_danger)
            })
            addView(L7Components.text(activity, activity.getString(R.string.l7_reset_message), secondary = true),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = L7Components.dp(activity, 16) })
        }
        var confirmed = false
        return L7Dialogs.builder(activity).setTitle(R.string.l7_reset_title).setView(message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.l7_reset_confirm) { _, _ ->
                if (!confirmed) { confirmed = true; onConfirm() }
            }.show()
    }

    private fun start(activity: Activity) {
        if (L7AppExit.exiting || activity.isFinishing || activity.isDestroyed) return
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(ProgressBar(activity))
            addView(L7Components.text(activity, activity.getString(R.string.l7_reset_progress)),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = L7Components.dp(activity, 20) })
        }
        val progress = L7Dialogs.builder(activity).setTitle(R.string.l7_reset_title)
            .setView(body).setCancelable(false).show()
        val owner = WeakReference(activity)
        L7AppExit.reset(activity.applicationContext, onRejected = {
            progress.dismiss()
            owner.get()?.takeUnless { it.isFinishing || it.isDestroyed }?.let(::failed)
        })
    }

    private fun failed(activity: Activity) {
        L7Dialogs.builder(activity).setTitle(R.string.l7_reset_failed)
            .setMessage(R.string.l7_reset_failed_hint).setNegativeButton(R.string.close, null)
            .setPositiveButton(R.string.l7_reset_open_settings) { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}"))
                if (runCatching { activity.startActivity(intent) }.isFailure) {
                    L7Dialogs.builder(activity).setTitle(R.string.l7_reset_failed)
                        .setMessage(R.string.l7_reset_manual_hint).setPositiveButton(R.string.close, null).show()
                }
            }.show()
    }
}
