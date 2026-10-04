package com.shilapi.xcertplay

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.WindowManager
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.CarPlayVpnService

/** 用户明确退出后先关闭会话，再移除任务与进程；清理超时仍执行退出。 */
internal object L7AppExit {
    @Volatile var exiting = false
        private set
    private var finishing = false
    private val handler = Handler(Looper.getMainLooper())

    fun confirm(context: Context): AlertDialog? {
        if (exiting) return null
        val ui = if (context is Activity) context else L7UiDensity.wrap(AppLocale.wrap(context))
        val dialog = L7Dialogs.builder(ui).setTitle(R.string.l7_exit_title)
            .setMessage(R.string.l7_exit_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.l7_exit_app) { _, _ -> exit(context.applicationContext) }.create()
        if (context !is Activity) dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
        return dialog
    }

    fun exit(context: Context) {
        if (exiting) return
        exiting = true
        L7StartupGuard.stopped()
        RemoteLogUpload.cancel()
        L7ProbeRunner.stop()
        L7DesktopNavigation.stop(context)
        L7DebugOverlayService.stop(context)
        val finish = Runnable { finish(context) }
        handler.postDelayed(finish, 6_000)
        CarPlayBackgroundSession.stop {
            handler.post { handler.removeCallbacks(finish); finish.run() }
        }
    }

    private fun finish(context: Context) {
        if (finishing) return
        finishing = true
        context.stopService(Intent(context, DiPlaySessionService::class.java))
        context.stopService(Intent(context, CarPlayVpnService::class.java))
        L7DebugOverlayService.stop(context)
        val manager = context.getSystemService(ActivityManager::class.java)
        manager.appTasks.forEach { runCatching { it.finishAndRemoveTask() } }
        // 留一个主线程周期完成窗口与服务销毁；仅允许结束本应用 UID/包名所属进程。
        handler.postDelayed({
            manager.runningAppProcesses.orEmpty().filter {
                it.uid == Process.myUid() && it.pid != Process.myPid() &&
                    (it.processName == context.packageName || it.processName.startsWith("${context.packageName}:"))
            }.forEach { Process.killProcess(it.pid) }
            Process.killProcess(Process.myPid())
        }, 150)
    }
}
