package com.shilapi.xcertplay

import android.app.Activity
import android.content.Context
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.transport.GalaxyUsbVerificationFailure
import com.shilapi.xcertplay.transport.GalaxyUsbVerificationSession
import com.shilapi.xcertplay.transport.GalaxyUsbVerificationPreferences

internal object GalaxyUsbVerificationSettings {
    private const val KEY = GalaxyUsbVerificationPreferences.KEY
    fun enabled(context: Context) = GalaxyUsbVerificationPreferences.enabled(context)
    fun add(activity: Activity, parent: LinearLayout) {
        val row = L7SettingRow(activity, activity.getString(R.string.usb_verification_method), activity.getString(R.string.usb_verification_hint))
        fun refresh() { row.setFeedback(activity.getString(if (enabled(activity)) R.string.usb_verification_experimental else R.string.usb_verification_standard)) }
        L7SettingsSection.add(parent, activity.getString(R.string.usb_verification_method)) { it.addView(row) }
        row.setOnClickListener {
            if (CarPlayBackgroundSession.hasSession()) {
                L7Dialogs.builder(activity).setMessage(R.string.usb_verification_stop_first).setPositiveButton(R.string.close, null).show()
            } else {
                val choices = arrayOf(activity.getString(R.string.usb_verification_standard), activity.getString(R.string.usb_verification_experimental))
                var choice = if (enabled(activity)) 1 else 0
                L7Dialogs.builder(activity).setTitle(R.string.usb_verification_method)
                    .setSingleChoiceItems(choices, choice) { _, selected -> choice = selected }
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.save) { _, _ ->
                        if (!GalaxyUsbVerificationSession.needsRecovery(activity) && !CarPlayBackgroundSession.hasSession()) {
                            activity.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().putBoolean(KEY, choice == 1).apply()
                            L7DebugLog.record("USB_VERIFY stage=SELECTION enabled=${choice == 1}")
                            refresh()
                        } else L7Dialogs.builder(activity).setMessage(R.string.usb_verification_recovery_required)
                            .setPositiveButton(R.string.close, null).show()
                    }.show()
            }
        }
        L7SettingsSection.actions(parent, activity.getString(R.string.usb_verification_recovery_hint)) { group ->
            val button = L7Components.actionButton(activity, activity.getString(R.string.usb_verification_restore)) { }
            button.setOnClickListener {
                if (CarPlayBackgroundSession.hasSession()) {
                    L7Dialogs.builder(activity).setMessage(R.string.usb_verification_stop_first).setPositiveButton(R.string.close, null).show()
                    return@setOnClickListener
                }
                if (!GalaxyUsbVerificationSession.needsRecovery(activity)) {
                    L7Dialogs.builder(activity).setMessage(R.string.usb_verification_no_recovery)
                        .setPositiveButton(R.string.close, null).show()
                    return@setOnClickListener
                }
                button.isEnabled = false
                val progress = L7Dialogs.builder(activity).setTitle(R.string.usb_verification_restore)
                    .setMessage(R.string.usb_verification_restoring).setCancelable(false).show()
                Thread({
                    val result = runCatching { GalaxyUsbVerificationSession.recover(activity.applicationContext, L7DebugLog::record) }
                    activity.runOnUiThread {
                        progress.dismiss()
                        if (!activity.isDestroyed && !activity.isFinishing) {
                            button.isEnabled = true
                            L7Dialogs.builder(activity).setMessage(if (result.isSuccess) activity.getString(R.string.usb_verification_restored)
                                else failure(activity, (result.exceptionOrNull() as? GalaxyUsbVerificationFailure)?.reason ?: GalaxyUsbVerificationFailure.Reason.RESTORE_FAILED))
                                .setPositiveButton(R.string.close, null).show()
                        }
                    }
                }, "galaxy-usb-restore").start()
            }
            group.addView(button)
        }
        refresh()
    }

    fun failure(context: Context, reason: GalaxyUsbVerificationFailure.Reason): String {
        val id = when (reason) {
            GalaxyUsbVerificationFailure.Reason.LOCAL_CONTROL_UNAVAILABLE, GalaxyUsbVerificationFailure.Reason.CONTROL_NOT_ROOT -> R.string.usb_verification_control_missing
            GalaxyUsbVerificationFailure.Reason.GADGET_RULE_MISSING, GalaxyUsbVerificationFailure.Reason.CONFIG_UNSUPPORTED,
            GalaxyUsbVerificationFailure.Reason.CONTROLLER_UNAVAILABLE, GalaxyUsbVerificationFailure.Reason.IPOD_NODE_UNAVAILABLE -> R.string.usb_verification_environment_missing
            GalaxyUsbVerificationFailure.Reason.RESTORE_FAILED, GalaxyUsbVerificationFailure.Reason.RESTORE_REQUIRED -> R.string.usb_verification_recovery_required
            GalaxyUsbVerificationFailure.Reason.PHONE_CAPABILITY_REJECTED, GalaxyUsbVerificationFailure.Reason.PHONE_ROLE_REJECTED,
            GalaxyUsbVerificationFailure.Reason.PHONE_UNAVAILABLE -> R.string.usb_verification_phone_failed
            else -> R.string.usb_verification_stage_failed
        }
        return context.getString(id) + "\n" + context.getString(R.string.usb_verification_reason, reason.name)
    }
}
