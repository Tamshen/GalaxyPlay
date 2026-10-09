package com.shilapi.xcertplay

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.shilapi.xcertplay.host.R

/** 在 Activity 构造和自动连接之前恢复状态，不能通过重建窗口或 USB 拉起绕过。 */
class L7Application : Application() {
    override fun onCreate() {
        super.onCreate()
        // 测试子进程只回传 Binder 摘要，由主进程写入既有日志队列。
        if (Application.getProcessName() == "$packageName:codec_probe") return
        GalaxyProfiles.install(this)
        L7DebugLog.initialize(this)
        if (Application.getProcessName() == packageName) L7StartupGuard.install(this)
    }
}

internal object L7StartupGuard {
    private var recovery: L7StartupRecovery? = null
    private var handlerInstalled = false
    private var writeFailed = false
    private var hostAuthorized = false
    private var noticeShown = false
    private var automaticClaimed = false
    private var generation = 0
    private val handler = Handler(Looper.getMainLooper())

    @Synchronized private fun state(context: Context): L7StartupRecovery {
        recovery?.let { return it }
        val app = context.applicationContext
        val next = L7StartupRecovery(app.getSharedPreferences("diplay", Context.MODE_PRIVATE))
        val exit = previousExit(app, next)
        writeFailed = !next.begin(System.currentTimeMillis(), Process.myPid(), exit)
        recovery = next
        if (next.failures > 0) L7DebugLog.record("启动保护 count=${next.failures} exit=$exit blocked=${next.blocked} " +
            "type=${next.lastType} frames=${next.lastFrames}")
        return next
    }

    @Synchronized fun install(context: Context) {
        L7WiredDiagnostics.recover(context)
        val state = state(context)
        if (handlerInstalled) return
        handlerInstalled = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(L7CrashRecorder(previous) {
            L7WiredDiagnostics.crash(context.applicationContext, it)
            state.recordCrash(it)
        })
    }

    private fun previousExit(context: Context, state: L7StartupRecovery): L7StartupRecovery.Exit {
        if (!state.pending || Build.VERSION.SDK_INT < 30) return L7StartupRecovery.Exit.UNKNOWN
        return runCatching {
            context.getSystemService(ActivityManager::class.java)
                ?.getHistoricalProcessExitReasons(context.packageName, state.previousPid, 3)
                ?.filter { it.processName == context.packageName && it.timestamp >= state.previousStart }
                ?.maxByOrNull { it.timestamp }?.let { L7StartupRecovery.classify(it.reason, it.status) }
        }.getOrNull() ?: L7StartupRecovery.Exit.UNKNOWN
    }

    fun allowAutomatic(context: Context): Boolean {
        if (state(context).blocked || writeFailed || automaticClaimed) return false
        automaticClaimed = true
        return true
    }

    fun authorizeHost(context: Context) {
        generation++
        hostAuthorized = true
        val ready = state(context).arm(System.currentTimeMillis(), Process.myPid())
        if (!ready) writeFailed = true
    }

    fun enterHost(activity: Activity): Boolean {
        val state = state(activity)
        if (!state.arm(System.currentTimeMillis(), Process.myPid())) writeFailed = true
        if ((state.blocked || writeFailed) && !hostAuthorized) {
            activity.startActivity(Intent(activity, GalaxySettingsActivity::class.java).putExtra("page", "home")
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            activity.finish()
            return false
        }
        generation++
        return true
    }

    fun healthyHome(activity: Activity) {
        val token = generation
        val ref = java.lang.ref.WeakReference(activity)
        handler.postDelayed({
            val owner = ref.get()
            if (token == generation && owner != null && !owner.isFinishing && !owner.isDestroyed && owner.hasWindowFocus() &&
                !CarPlayBackgroundSession.hasSession()) state(owner).healthy()
        }, 30_000)
    }

    fun connected(context: Context, currentSession: () -> Boolean) {
        val token = ++generation
        val app = context.applicationContext
        handler.postDelayed({
            if (token == generation && currentSession()) state(app).healthy()
        }, 30_000)
    }

    fun stopped() { generation++; hostAuthorized = false; recovery?.healthy() }

    fun setEnabled(context: Context, enabled: Boolean) {
        generation++
        writeFailed = !state(context).setEnabled(enabled, System.currentTimeMillis(), Process.myPid())
    }

    fun showNotice(activity: Activity, onLogs: () -> Unit) {
        if (L7WiredDiagnostics.showNotice(activity, onLogs)) return
        val state = state(activity)
        if (noticeShown || !state.notice && !writeFailed) return
        noticeShown = true
        L7Dialogs.builder(activity).setTitle(R.string.l7_startup_recovery_title)
            .setMessage(if (writeFailed) R.string.l7_startup_recovery_storage else R.string.l7_startup_recovery_hint)
            .setNegativeButton(R.string.close) { _, _ -> state.acknowledge() }
            .setPositiveButton(R.string.l7_log_view_short) { _, _ -> state.acknowledge(); onLogs() }.show()
    }
}

/** 记录后仍交给 Android 的原处理器终止，绝不吞异常并继续运行损坏的进程。 */
internal class L7CrashRecorder(
    private val previous: Thread.UncaughtExceptionHandler?, private val record: (Throwable) -> Unit,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, error: Throwable) {
        runCatching { record(error) }
        if (previous != null) previous.uncaughtException(thread, error)
        else { Process.killProcess(Process.myPid()); kotlin.system.exitProcess(10) }
    }
}
