package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.view.WindowManager
import com.shilapi.xcertplay.host.R

/** 三处菜单共用连接动作；确认只对打开弹窗时的会话有效。 */
internal object GalaxyConnectionMenu {
    fun select(context: Context, openSettings: () -> Unit): AlertDialog? {
        val expected = CarPlayBackgroundSession.snapshot()?.controller
        if (!CarPlayBackgroundSession.active || expected == null) {
            openSettings()
            return null
        }
        val ui = if (context is Activity) context else L7UiDensity.wrap(AppLocale.wrap(context))
        val dialog = L7Dialogs.builder(ui)
            .setTitle(R.string.l7_nav_disconnect)
            .setMessage(R.string.l7_disconnect_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.l7_nav_disconnect) { _, _ ->
                if (CarPlayBackgroundSession.snapshot()?.controller === expected &&
                    CarPlayBackgroundSession.active && !CarPlayBackgroundSession.isStopping()) {
                    L7DebugLog.record("菜单断开连接 CONFIRMED")
                    CarPlayBackgroundSession.stop()
                }
            }.create()
        if (context !is Activity) dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
        return dialog
    }
}
