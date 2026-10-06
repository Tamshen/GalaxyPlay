package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.transport.IphoneUsbMatcher
import java.util.concurrent.Executors

/** USB 检查只读枚举与权限；真正申请权限及切换设备仍由原有有线连接流程负责。 */
internal data class L7UsbReadiness(val host: Boolean, val devices: Int = 0, val granted: Int = 0, val failed: Boolean = false) {
    companion object {
        fun read(activity: Activity): L7UsbReadiness = try {
            val host = activity.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)
            val manager = activity.getSystemService(UsbManager::class.java)
            if (manager == null) L7UsbReadiness(host, failed = true) else {
                val matcher = IphoneUsbMatcher.appleVendor()
                val devices = manager.deviceList.values.filter { matcher.matches(it.vendorId, it.productId) }
                L7UsbReadiness(host || devices.isNotEmpty(), devices.size, devices.count { manager.hasPermission(it) })
            }
        } catch (_: Exception) { L7UsbReadiness(false, failed = true) }
    }
}

internal class L7WiredSettings(
    private val activity: Activity, parent: LinearLayout,
    private val onConnect: () -> Unit, onDisconnect: () -> Unit,
    private val inspect: () -> L7UsbReadiness = { L7UsbReadiness.read(activity) },
) {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "l7-usb-check") }
    private val main = Handler(Looper.getMainLooper())
    private var disposed = false
    private var busy = false
    private var generation = 0
    private var dialog: AlertDialog? = null
    private val device = L7SettingRow(activity, text(R.string.l7_usb_step1))
    private val permission = L7SettingRow(activity, text(R.string.l7_usb_step2))
    private lateinit var refresh: Button
    val connectButton: Button
    val disconnectButton: Button

    init {
        L7SettingsSection.add(parent, text(R.string.l7_usb_steps), text(R.string.l7_connection_usb_hint)) { card ->
            card.addView(device)
            card.addView(permission)
            card.addView(L7SettingRow(activity, text(R.string.l7_usb_step3), text(R.string.l7_usb_phone_hint)))
        }
        connectButton = L7Components.actionButton(activity, text(R.string.l7_home_usb), primary = true) {
            if (CarPlayBackgroundSession.hasSession()) onConnect() else check(connect = true)
        }
        disconnectButton = L7Components.actionButton(activity, text(R.string.disconnect), click = onDisconnect)
            .apply { visibility = android.view.View.GONE }
        L7SettingsSection.actions(parent, text(R.string.l7_usb_snapshot_hint)) { group ->
            refresh = L7Components.actionButton(activity, text(R.string.l7_usb_refresh)) { check(explicit = true) }
            listOf(connectButton, refresh, disconnectButton).forEachIndexed { index, view ->
                group.addView(view, LinearLayout.LayoutParams(-1, -2).apply {
                    if (index > 0) topMargin = L7Components.dp(activity, 12)
                })
            }
        }
        check()
    }

    fun check(connect: Boolean = false, explicit: Boolean = false) {
        if (busy || disposed) return
        busy = true
        val token = ++generation
        refresh.isEnabled = false; connectButton.isEnabled = false
        device.setFeedback(text(R.string.l7_usb_checking)); permission.setFeedback("")
        worker.execute {
            val result = runCatching(inspect).getOrElse { L7UsbReadiness(false, failed = true) }
            main.post {
                if (disposed || token != generation || activity.isDestroyed || activity.isFinishing) return@post
                busy = false; refresh.isEnabled = true; connectButton.isEnabled = true
                val message = when {
                    result.failed -> text(R.string.l7_usb_check_failed)
                    !result.host -> text(R.string.l7_usb_no_host)
                    result.devices == 0 -> text(R.string.l7_usb_missing)
                    else -> activity.getString(R.string.l7_usb_found, result.devices)
                }
                device.setFeedback(message, error = result.failed || !result.host)
                permission.setFeedback(text(when {
                    result.failed || result.devices == 0 -> R.string.l7_usb_permission_no_device
                    result.granted == result.devices -> R.string.l7_usb_permission_ok
                    else -> R.string.l7_usb_permission_wait
                }))
                if (connect && !result.failed && result.host && result.devices > 0) onConnect()
                else if (connect || explicit && (result.failed || !result.host)) showProblem(result, message, connect)
            }
        }
    }

    private fun showProblem(result: L7UsbReadiness, message: String, connect: Boolean) {
        dialog?.dismiss()
        val builder = L7Dialogs.builder(activity).setTitle(R.string.l7_usb_not_ready).setMessage(message)
            .setNegativeButton(R.string.close, null)
        if (connect && !result.failed && result.host && result.devices == 0) {
            builder.setPositiveButton(R.string.l7_usb_wait_connect) { _, _ -> onConnect() }
        } else builder.setPositiveButton(R.string.l7_usb_refresh) { _, _ -> check(explicit = true) }
        dialog = builder.show()
    }

    fun update(preparing: Boolean) {
        val session = CarPlayBackgroundSession.hasSession()
        val label = text(when {
            preparing -> R.string.l7_preparing
            CarPlayBackgroundSession.active -> R.string.l7_resume_projection
            session -> R.string.l7_view_connection
            else -> R.string.l7_home_usb
        })
        if (connectButton.text.toString() != label) { connectButton.text = label; L7Icons.decorate(connectButton) }
        connectButton.isEnabled = !busy && !preparing
        disconnectButton.visibility = if (session) android.view.View.VISIBLE else android.view.View.GONE
    }

    fun background() {
        generation++; busy = false
        refresh.isEnabled = true; connectButton.isEnabled = true
        dialog?.dismiss(); dialog = null
    }
    fun dispose() {
        background(); disposed = true
        worker.shutdownNow(); main.removeCallbacksAndMessages(null)
    }
    private fun text(id: Int) = activity.getString(id)
}
