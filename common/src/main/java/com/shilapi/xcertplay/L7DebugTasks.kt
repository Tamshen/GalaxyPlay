package com.shilapi.xcertplay

import android.app.Activity
import com.shilapi.xcertplay.host.R

/** 宿主独占一个任务窗口，页面重绘不会重新启动任务；后台/销毁停止用户正在等待的操作。 */
internal class L7DebugTasks(private val activity: Activity, private val onResults: () -> Unit) {
    private var window: L7TaskProgressDialog? = null
    private val labels = L7ProbeLabels(activity)

    fun collect(onlyId: String? = null): Boolean {
        if (window != null) return false
        if (!L7ProbeRunner.start(activity, L7ProbeEnvironment.window(activity), onlyId)) {
            L7Notice.show(activity, text(R.string.l7_probe_busy)); return false
        }
        showCollection()
        return true
    }

    fun upload() {
        if (window != null) return
        if (L7ProbeRunner.clearing) { L7Notice.show(activity, text(R.string.l7_log_clear_busy)); return }
        val phase = RemoteLogUpload.status.phase
        // 失败后再次进入仍先展示重试确认，不因打开窗口再次发送。
        if (phase !in listOf(RemoteLogUpload.Phase.FAILED, RemoteLogUpload.Phase.UPLOADING) &&
            !RemoteLogUpload.start(activity)) {
            L7Notice.show(activity, text(R.string.l7_log_config_first)); return
        }
        showUpload()
    }

    fun resume() {
        if (window != null) { window?.resume(); return }
        when {
            RemoteLogUpload.status.phase == RemoteLogUpload.Phase.UPLOADING -> showUpload()
            L7ProbeRunner.current?.phase == L7ProbePhase.RUNNING -> showCollection()
        }
    }

    fun background() { window?.stop(); window?.pause() }
    fun dispose(changingConfiguration: Boolean) {
        if (!changingConfiguration) window?.stop()
        window?.dismiss(); window = null
    }

    private fun showCollection() {
        val id = L7ProbeRunner.current?.id ?: return
        window = L7TaskProgressDialog(activity, R.string.l7_task_collect, R.string.l7_task_stop_collect,
            R.string.l7_task_end_collect, { collectionState(id) }, { L7ProbeRunner.stop() },
            onResult = onResults, onDismiss = { window = null }).also { it.show() }
    }

    private fun collectionState(id: String): L7TaskProgress {
        val report = L7ProbeRunner.current?.takeIf { it.id == id }
            ?: return L7TaskProgress(false, text(R.string.l7_probe_interrupted), error = true)
        val running = report.phase == L7ProbePhase.RUNNING
        val busy = L7ProbeRunner.busy
        val count = report.items.count { it.reason != "NOT_RUN" }
        val message = when {
            running && report.expected == 0 -> text(R.string.l7_task_preparing_collect)
            running -> activity.getString(R.string.l7_probe_progress, count, report.expected)
            report.phase == L7ProbePhase.COMPLETED && busy -> text(R.string.l7_task_saving)
            else -> labels.phase(report)
        }
        val detail = buildList {
            if (running) report.items.firstOrNull { it.reason == "NOT_RUN" }?.let {
                add(activity.getString(R.string.l7_task_current, labels.name(it)))
            } else if (busy && report.phase != L7ProbePhase.COMPLETED) add(text(R.string.l7_task_stopping_collect))
            else add(labels.summary(report))
            if (L7ProbeRunner.storageFailed) add(text(R.string.l7_probe_storage_failed))
            if (L7ProbeRunner.logFailed) add(text(R.string.l7_probe_log_failed))
            else if (!busy) add(activity.getString(R.string.l7_probe_logged, L7ProbeLog.batch(report)))
        }.joinToString("\n\n")
        return L7TaskProgress(running, message, detail, count, report.expected, waiting = busy && !running,
            result = !running && !busy, error = report.phase in listOf(L7ProbePhase.TIMED_OUT, L7ProbePhase.INTERRUPTED) ||
                L7ProbeRunner.storageFailed || L7ProbeRunner.logFailed)
    }

    private fun showUpload() {
        val config = RemoteLogConfig.load(activity)
        val name = config.forDevice(RemoteLogDevice.id(activity)).stream
        window = L7TaskProgressDialog(activity, R.string.l7_log_upload, R.string.l7_task_stop_upload,
            R.string.l7_task_end_upload, { uploadState(name) }, { RemoteLogUpload.cancel() },
            onRetry = { RemoteLogUpload.start(activity, retry = true) },
            onDismiss = { window = null }).also { it.show() }
    }

    private fun uploadState(name: String): L7TaskProgress {
        val status = RemoteLogUpload.status
        val running = status.phase == RemoteLogUpload.Phase.UPLOADING
        val failed = status.phase == RemoteLogUpload.Phase.FAILED
        val message = text(when (status.phase) {
            RemoteLogUpload.Phase.UPLOADING -> if (status.totalBatches == 0) R.string.l7_task_preparing_upload else R.string.l7_log_uploading
            RemoteLogUpload.Phase.SUCCESS -> R.string.l7_task_uploaded
            RemoteLogUpload.Phase.FAILED -> R.string.l7_task_upload_failed
            RemoteLogUpload.Phase.EMPTY -> R.string.l7_log_empty_title
            else -> R.string.l7_log_cancelled
        })
        val summary = L7RemoteLogSettings.statusText(activity, status, name)
        val detail = (if (running) summary.substringAfter('\n', "") else summary) +
            if (running || status.phase == RemoteLogUpload.Phase.CANCELLED) "\n\n" + text(R.string.l7_task_upload_stopped) else ""
        return L7TaskProgress(running, message, detail, status.uploadedLines, status.totalLines,
            retry = failed, error = failed, showProgress = status.phase != RemoteLogUpload.Phase.EMPTY)
    }

    private fun text(id: Int) = activity.getString(id)
}
