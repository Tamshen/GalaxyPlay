package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.CarHotspotStatus
import com.shilapi.xcertplay.orchestration.ManualHotspotValidation

/** 发起无线连接时先准备原生热点；只有当前页面仍在等待才继续建链。 */
internal class L7WirelessHotspotGate(private val activity: Activity, private val task: L7HotspotTask) {
    private var dialog: AlertDialog? = null
    private var ready: (() -> Unit)? = null
    private var setup: (() -> Unit)? = null

    fun start(onReady: () -> Unit, onSetup: () -> Unit) {
        if (ready != null) return
        if (!task.start()) {
            dialog = L7Dialogs.builder(activity).setTitle(R.string.l7_hotspot_not_started)
                .setMessage(if (task.status.busy) R.string.l7_hotspot_busy else task.status.message)
                .setPositiveButton(R.string.close, null).show()
            return
        }
        ready = onReady; setup = onSetup
        dialog = L7Dialogs.builder(activity).setTitle(R.string.built_in_car_hotspot)
            .setMessage(R.string.l7_hotspot_starting)
            .setNegativeButton(R.string.cancel) { _, _ -> cancel() }
            .setCancelable(false).show()
    }

    fun update() {
        val continuation = ready ?: return
        if (task.status.busy) return
        val status = task.status.message
        val configure = setup
        ready = null; setup = null
        dialog?.dismiss(); dialog = null
        if (!L7Agreement.canUse(activity) || activity.isFinishing || activity.isDestroyed || L7AppExit.exiting) return
        if (status == R.string.l7_hotspot_ready || status == R.string.l7_hotspot_ready_no_details) {
            continuation(); return
        }
        if (status == R.string.l7_hotspot_cancelled) return
        val feedback = L7HotspotFeedback.state(activity, task.status)
        val builder = L7Dialogs.builder(activity).setTitle(feedback.message).setMessage(feedback.detail)
            .setNegativeButton(R.string.connection_setup) { _, _ -> configure?.invoke() }
            .setPositiveButton(if (status == R.string.l7_hotspot_start_permission) R.string.l7_hotspot_grant else R.string.open_car_hotspot_settings) { _, _ ->
                L7HotspotFeedback.recover(activity, status)
            }
        // 固件隐藏 AP 状态时保留既有手动接入能力，由后续接口地址检测判断是否就绪。
        if (status == R.string.l7_hotspot_unsupported && CarHotspotStatus.isEnabled(activity) == null &&
            ManualHotspotValidation.error(AirPlayPersistence.loadManualHotspotSsid(activity),
                AirPlayPersistence.loadManualHotspotPassphrase(activity)) == null) {
            builder.setNeutralButton(R.string.l7_hotspot_use_saved) { _, _ -> continuation() }
        }
        dialog = builder.show()
    }

    fun cancel() {
        ready = null; setup = null
        task.cancel()
        dialog?.dismiss(); dialog = null
    }
}
