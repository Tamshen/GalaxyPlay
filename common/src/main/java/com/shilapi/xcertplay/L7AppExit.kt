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
        stop(context) { finish(context) }
    }

    /** 系统统一清理应用数据并结束进程，避免遗漏未知旧配置或被内存缓存写回。 */
    fun reset(context: Context, onRejected: () -> Unit,
              clearData: () -> Boolean = { context.getSystemService(ActivityManager::class.java).clearApplicationUserData() }) {
        stop(context) {
            stopServices(context)
            if (!runCatching(clearData).getOrDefault(false)) {
                exiting = false
                onRejected()
            }
        }
    }

    private fun stop(context: Context, completion: () -> Unit) {
        if (exiting) return
        exiting = true
        L7ReportingTests.stop("APP_EXIT")
        L7WiredDiagnostics.stopped(context)
        L7StartupGuard.stopped()
        RemoteLogUpload.cancel()
        L7ProbeRunner.stop()
        L7DesktopNavigation.stop(context)
        L7DebugOverlayService.stop(context)
        var completed = false
        val finish = Runnable {
            if (!completed) { completed = true; completion() }
        }
        handler.postDelayed(finish, 6_000)
        CarPlayBackgroundSession.stop {
            handler.post { handler.removeCallbacks(finish); finish.run() }
        }
    }

    private fun finish(context: Context) {
        if (finishing) return
        finishing = true
        stopServices(context)
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

    private fun stopServices(context: Context) {
        context.stopService(Intent(context, DiPlaySessionService::class.java))
        context.stopService(Intent(context, CarPlayVpnService::class.java))
        L7DebugOverlayService.stop(context)
    }
}
