package com.shilapi.xcertplay

import android.content.Context

/** 单个手动任务顺序发送各批；重试跳过已确认批次，保留未确认批次的事件编号。 */
internal object RemoteLogUpload {
    enum class Phase { IDLE, UPLOADING, SUCCESS, FAILED, CANCELLED }
    data class Status(
        val phase: Phase = Phase.IDLE, val id: String = "", val code: Int = 0,
        val completedBatches: Int = 0, val totalBatches: Int = 0,
        val uploadedLines: Int = 0, val totalLines: Int = 0,
        val omittedLines: Int = 0, val shortenedSources: Int = 0,
        val finishedAt: Long = 0,
    )
    private data class Pending(val config: RemoteLogConfig, val report: RemoteLogReport, var next: Int = 0)
    @Volatile var status = Status()
        private set
    private var generation = 0L
    private var transport: RemoteLogTransport? = null
    private var pending: Pending? = null

    @Synchronized fun start(context: Context, retry: Boolean = false): Boolean {
        val app = context.applicationContext
        val saved = RemoteLogConfig.load(app)
        if (!saved.valid() || !allowed(app) || status.phase == Phase.UPLOADING) return false
        val config = saved.forDevice(RemoteLogDevice.id(app))
        val retained = if (retry) pending?.takeIf { it.config == config } else null
        if (retained == null) pending = null
        val run = ++generation
        val sender = RemoteLogTransport().also { transport = it }
        status = retained?.state(Phase.UPLOADING) ?: Status(Phase.UPLOADING)
        Thread({ upload(app, config, retained, run, sender) }, "l7-manual-log-upload")
            .apply { isDaemon = true; start() }
        return true
    }

    private fun upload(app: Context, config: RemoteLogConfig, retained: Pending?, run: Long, sender: RemoteLogTransport) {
        var task = retained
        val code = runCatching {
            val current = task ?: Pending(config, RemoteLogReport.collect(app)).also { task = it }
            synchronized(this) {
                check(run == generation && allowed(app))
                pending = current
                status = current.state(Phase.UPLOADING)
            }
            sendBatches(app, current, run, sender)
        }.getOrDefault(0)
        sender.close()
        synchronized(this) {
            if (run == generation) {
                transport = null
                val phase = if (code in 200..299) Phase.SUCCESS else Phase.FAILED
                status = task?.state(phase, code) ?: Status(phase, code = code)
                if (phase == Phase.SUCCESS) {
                    status = status.copy(finishedAt = System.currentTimeMillis())
                    runCatching { RemoteLogHistory.save(app, RemoteLogHistory.Entry(status.finishedAt, status.totalLines)) }
                    pending = null
                }
            }
        }
    }

    private fun sendBatches(app: Context, task: Pending, run: Long, sender: RemoteLogTransport): Int {
        while (task.next < task.report.batches.size) {
            synchronized(this) { check(run == generation && allowed(app)) }
            val code = sender.send(task.config, task.report.batches[task.next])
            if (code !in 200..299) return code
            synchronized(this) {
                check(run == generation && allowed(app))
                task.next++
                status = task.state(Phase.UPLOADING)
            }
        }
        return 200
    }

    private fun Pending.state(phase: Phase, code: Int = 0) = Status(
        phase, report.id, code, next, report.batches.size,
        report.batches.take(next).sumOf { it.lineCount }, report.lineCount, report.omittedLines, report.shortenedSources,
    )
    private fun allowed(context: Context) = L7Agreement.accepted(context) && !L7AppExit.exiting

    @Synchronized fun cancel() {
        generation++
        // UI 不等待 socket 释放；代次检查阻止旧任务继续发送下一批或覆盖新状态。
        val old = transport
        transport = null
        if (old != null) Thread({ old.close() }, "l7-log-upload-cancel").apply { isDaemon = true; start() }
        pending = null
        status = status.copy(phase = Phase.CANCELLED)
    }
}
