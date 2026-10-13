package com.shilapi.xcertplay

import android.os.Handler
import android.os.Looper
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.shilapi.xcertplay.host.R
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService

/** 关于页的显式更新任务；页面退出后停止网络，旧请求不能打开安装界面。 */
internal class GalaxyAppUpdate(
    private val activity: ComponentActivity,
    private val transfer: GalaxyUpdateSource = GalaxyUpdateTransfer(),
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { task -> Thread(task, "GalaxyPlay-update") },
    private val verify: (File, GalaxyUpdateRelease, GalaxyUpdateRequest) -> Unit = { file, release, token ->
        GalaxyUpdateApk.verify(activity.applicationContext, file, release, token)
    },
) : DefaultLifecycleObserver {
    private val main = Handler(Looper.getMainLooper())
    private val installer = GalaxyUpdateInstaller(activity, status = { message ->
        state = state.copy(message = text(message), error = message != R.string.update_permission_ready &&
            message != R.string.update_installer_returned)
        dialog?.resume()
    })
    @Volatile private var request: GalaxyUpdateRequest? = null
    @Volatile private var state = L7TaskProgress(false, "")
    private var dialog: L7TaskProgressDialog? = null
    @Volatile private var closed = false
    private var ready: File? = null
    private var restoreChecked = false
    private var retry: () -> Unit = {}
    private var result: () -> Unit = {}

    init { activity.lifecycle.addObserver(this) }

    fun check() {
        if (closed || request != null) return
        run(R.string.update_checking, { check() }) { token ->
            val latest = transfer.latest(activity.packageName, token)
            @Suppress("DEPRECATION")
            val installed = activity.packageManager.getPackageInfo(activity.packageName, 0).longVersionCode
            main.post {
                if (!current(token)) return@post
                val next = latest.newerThan(installed)
                val compatible = latest.minSdk == null || latest.minSdk <= Build.VERSION.SDK_INT
                state = L7TaskProgress(false, text(when {
                    !next -> R.string.update_current
                    !compatible -> R.string.update_incompatible
                    else -> R.string.update_available
                }, latest.version), detail = if (next) summary(latest) else "", result = next && compatible,
                    resultLabel = R.string.update_download, showProgress = false)
                result = { download(latest) }
                finish(token)
            }
        }
    }

    private fun download(latest: GalaxyUpdateRelease) {
        run(R.string.update_downloading, { download(latest) }) { token ->
            var file: File? = null
            try {
                file = transfer.download(latest, File(activity.cacheDir, "app-updates"), token) { completed ->
                    if (current(token)) state = state.copy(completed = completed.toInt(), total = latest.size.toInt(),
                        detail = text(R.string.update_download_progress, completed * 100 / latest.size))
                }
                if (current(token)) state = state.copy(message = text(R.string.update_verifying))
                val verified = file
                GalaxyUpdateFiles.protect(verified).use {
                    verify(verified, latest, token)
                    token.check()
                    GalaxyUpdateCache.save(activity.applicationContext, verified, latest)
                }
                main.post {
                    if (!current(token)) { verified.delete(); return@post }
                    ready?.takeIf { it != verified }?.let { GalaxyUpdateFiles.delete(it.parentFile!!, it) }
                    ready = verified
                    state = L7TaskProgress(false, text(R.string.update_ready, latest.version), detail = text(R.string.update_ready_hint),
                        result = true, resultLabel = R.string.update_install, showProgress = false)
                    result = { install(latest, verified) }
                    finish(token)
                }
            } catch (error: Exception) { file?.delete(); throw error }
        }
    }

    private fun install(latest: GalaxyUpdateRelease, file: File) {
        // 安装前重新验证私有缓存和当前已安装版本，授权或返回不自动触发安装。
        run(R.string.update_verifying, { install(latest, file) }) { token ->
            GalaxyUpdateFiles.protect(file).use {
                verify(file, latest, token)
                GalaxyUpdateCache.save(activity.applicationContext, file, latest)
            }
            main.post {
                if (!current(token)) return@post
                state = L7TaskProgress(false, text(R.string.update_ready, latest.version), detail = text(R.string.update_ready_hint),
                    result = true, resultLabel = R.string.update_install, showProgress = false)
                result = { install(latest, file) }
                finish(token)
                installer.install(file)
            }
        }
    }

    private fun run(message: Int, again: () -> Unit, work: (GalaxyUpdateRequest) -> Unit) {
        if (closed || request != null) return
        val token = GalaxyUpdateRequest()
        request = token; retry = again
        state = L7TaskProgress(true, text(message), waiting = message != R.string.update_downloading)
        if (dialog == null) {
            lateinit var window: L7TaskProgressDialog
            window = L7TaskProgressDialog(activity, R.string.update_check, R.string.update_stop,
                R.string.update_close_hint, { state }, { stop() }, onRetry = { retry() }, onResult = {
                    val action = result
                    dialog = null
                    action()
                }, onDismiss = {
                    // 旧窗口的延后关闭通知不能取消已经启动的下一阶段。
                    if (dialog === window) { stop(); dialog = null }
                }, fixedHeight = true)
            dialog = window
            window.show()
        } else dialog?.resume()
        worker.execute {
            try { work(token) }
            catch (error: Exception) {
                main.post {
                    if (!current(token)) return@post
                    val reason = (error as? GalaxyUpdateException)?.reason ?: GalaxyUpdateFailure.NETWORK
                    state = L7TaskProgress(false, text(failure(reason)), retry = true, error = true, showProgress = false)
                    finish(token)
                }
            }
        }
    }

    private fun current(token: GalaxyUpdateRequest) = !closed && request === token
    private fun finish(token: GalaxyUpdateRequest) {
        if (request === token) request = null
        dialog?.resume()
    }
    private fun stop() {
        request?.cancel(); request = null
        if (state.running) state = L7TaskProgress(false, text(R.string.update_stopped), retry = true, showProgress = false)
    }
    private fun text(id: Int, vararg values: Any) = activity.getString(id, *values)
    private fun summary(value: GalaxyUpdateRelease) = text(R.string.update_summary, value.version,
        android.text.format.Formatter.formatFileSize(activity, value.size), value.notes.ifBlank { text(R.string.update_no_notes) })
    private fun failure(reason: GalaxyUpdateFailure) = when (reason) {
        GalaxyUpdateFailure.MANIFEST -> R.string.update_bad_manifest
        GalaxyUpdateFailure.INTEGRITY -> R.string.update_bad_integrity
        GalaxyUpdateFailure.APK -> R.string.update_bad_apk
        GalaxyUpdateFailure.SIGNATURE -> R.string.update_bad_signature
        GalaxyUpdateFailure.STORAGE -> R.string.update_no_storage
        GalaxyUpdateFailure.CANCELLED -> R.string.update_stopped
        else -> R.string.update_network_error
    }
    override fun onPause(owner: LifecycleOwner) { dialog?.pause() }
    override fun onResume(owner: LifecycleOwner) {
        dialog?.resume()
        if (restoreChecked || !L7Agreement.accepted(activity)) return
        restoreChecked = true
        val pending = GalaxyUpdateCache.load(activity) ?: return
        ready = pending.file
        restore(pending)
    }
    private fun restore(pending: GalaxyUpdateCache.Pending) {
        run(R.string.update_verifying, { restore(pending) }) { token ->
            GalaxyUpdateFiles.protect(pending.file).use { verify(pending.file, pending.release, token) }
            main.post {
                if (!current(token)) return@post
                state = L7TaskProgress(false, text(R.string.update_ready, pending.release.version),
                    detail = text(if (activity.packageManager.canRequestPackageInstalls()) R.string.update_permission_ready
                        else R.string.update_ready_hint), result = true, resultLabel = R.string.update_install, showProgress = false)
                result = { install(pending.release, pending.file) }
                finish(token)
            }
        }
    }
    override fun onStop(owner: LifecycleOwner) { stop() }
    override fun onDestroy(owner: LifecycleOwner) {
        closed = true; stop(); worker.shutdownNow()
        dialog?.dismiss(); dialog = null
        main.removeCallbacksAndMessages(null)
        activity.lifecycle.removeObserver(this)
    }
}
