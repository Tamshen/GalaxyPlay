package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Toast
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayTransport

/** 三处菜单共用连接动作；确认只对打开弹窗时的会话有效。 */
internal object GalaxyConnectionMenu {
    private val handler = Handler(Looper.getMainLooper())
    private var pending = false
    private var request = 0

    fun select(context: Context, openSettings: () -> Unit): AlertDialog? {
        val expected = CarPlayBackgroundSession.snapshot()?.controller
        if (!CarPlayBackgroundSession.active || expected == null) {
            openSettings()
            return null
        }
        val ui = if (context is Activity) context else L7UiDensity.wrap(AppLocale.wrap(context))
        val dialog = L7Dialogs.builder(ui)
            .setTitle(R.string.l7_nav_reconnect)
            .setMessage(R.string.l7_menu_reconnect_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.l7_nav_reconnect) { _, _ ->
                if (CarPlayBackgroundSession.snapshot()?.controller === expected &&
                    CarPlayBackgroundSession.active && !CarPlayBackgroundSession.isStopping()) {
                    reconnect(context, expected)
                }
            }.create()
        if (context !is Activity) dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
        return dialog
    }

    /** 停止会销毁原投屏宿主；用 Application 等释放完成后再手动拉起，沿用实际传输方式。 */
    private fun reconnect(context: Context, expected: CarPlayController) {
        val app = context.applicationContext
        if (pending || expected.isClosed() || L7AppExit.exiting || !L7Agreement.canUse(app)) return
        val wireless = expected.transport == CarPlayTransport.WIRELESS
        pending = true
        val token = ++request
        L7DebugLog.record("菜单重连 CONFIRMED transport=${expected.transport}")
        runCatching {
            CarPlayBackgroundSession.stop {
                handler.post {
                    if (!pending || token != request) return@post
                    pending = false
                    if (L7AppExit.exiting || !L7Agreement.canUse(app) || CarPlayBackgroundSession.hasSession()) {
                        L7DebugLog.record("菜单重连 CANCELLED")
                        return@post
                    }
                    runCatching {
                        AirPlayPersistence.saveWirelessEnabled(app, wireless)
                        L7StartupGuard.authorizeHost(app)
                        app.startActivity(Intent(app, CarPlayHostActivity::class.java).addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                        L7DebugLog.record("菜单重连 REQUESTED")
                    }.onFailure { failed(app, it) }
                }
            }
        }.onFailure {
            pending = false
            failed(app, it)
        }
    }

    private fun failed(context: Context, error: Throwable) {
        L7DebugLog.record("菜单重连 FAILED type=${error.javaClass.simpleName}")
        Toast.makeText(context, R.string.l7_menu_reconnect_failed, Toast.LENGTH_LONG).show()
    }
}
