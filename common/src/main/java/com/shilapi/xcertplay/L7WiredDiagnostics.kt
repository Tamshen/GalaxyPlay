package com.shilapi.xcertplay

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import com.shilapi.xcertplay.host.R

/** 关键证据单独保存，上传尾部优先保留，进程恢复时不依赖内存日志。 */
internal object L7WiredDiagnostics {
    @Synchronized private fun journal(context: Context) = L7WiredJournal(
        context.getSharedPreferences("l7_wired_diagnostics", Context.MODE_PRIVATE),
        System::currentTimeMillis, SystemClock::elapsedRealtime,
    )

    @Synchronized fun recover(context: Context) {
        runCatching { journal(context).recover(Process.myPid()) { pid, since ->
            if (Build.VERSION.SDK_INT < 30) null else runCatching {
                context.getSystemService(ActivityManager::class.java)
                    ?.getHistoricalProcessExitReasons(context.packageName, pid, 3)
                    ?.filter { it.processName == context.packageName && it.timestamp >= since && it.timestamp <= System.currentTimeMillis() }
                    ?.maxByOrNull { it.timestamp }?.reason
            }.getOrNull()
        } }
    }

    @Synchronized fun begin(context: Context): String = runCatching {
        journal(context).begin(Process.myPid(), context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty())
    }.getOrElse { "unavailable" }

    /** 同进程重建沿用未结束尝试；进程重启另建记录，保留旧 PID 的退出和崩溃证据。 */
    @Synchronized fun beginOrResume(context: Context, saved: String?): String =
        runCatching { journal(context).resume(Process.myPid(), saved) }.getOrNull() ?: begin(context)

    @Synchronized fun event(context: Context, id: String?, phase: String, result: String,
                            error: Throwable? = null, outcome: String? = null) {
        if (id == null) return
        runCatching { journal(context).event(id, phase, result, error, outcome) }
        L7DebugLog.record("USB_STARTUP attempt=$id phase=$phase result=$result" +
            (error?.let { " exception=${it.javaClass.simpleName}" } ?: ""))
    }

    @Synchronized fun crash(context: Context, error: Throwable) {
        runCatching { journal(context).crash(Process.myPid(), error) }
    }

    @Synchronized fun stopped(context: Context) {
        val journal = journal(context)
        journal.current(Process.myPid())?.let { journal.event(it, "STOP", "EXPLICIT", outcome = "CANCELLED") }
    }

    @Synchronized fun report(context: Context): List<String> = runCatching { journal(context).report() }
        .getOrElse { listOf("USB_STARTUP available=false error=${it.javaClass.simpleName}") }

    @Synchronized fun clear(context: Context) { journal(context).clear() }

    fun showNotice(activity: Activity, logs: () -> Unit): Boolean {
        val journal = journal(activity)
        if (!journal.notice()) return false
        L7Dialogs.builder(activity).setTitle(R.string.l7_usb_previous_title)
            .setMessage(R.string.l7_usb_previous_hint)
            .setNegativeButton(R.string.close) { _, _ -> journal.acknowledge() }
            .setPositiveButton(R.string.l7_log_view_short) { _, _ -> journal.acknowledge(); logs() }.show()
        return true
    }
}
