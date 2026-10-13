package com.shilapi.xcertplay

import android.content.Context
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** 单个手动任务顺序发送各批；重试跳过已确认批次，保留未确认批次的事件编号。 */
internal object RemoteLogUpload {
    enum class Phase { IDLE, UPLOADING, SUCCESS, FAILED, CANCELLED, EMPTY }
    data class Status(
        val phase: Phase = Phase.IDLE, val id: String = "", val code: Int = 0,
        val completedBatches: Int = 0, val totalBatches: Int = 0,
        val uploadedLines: Int = 0, val totalLines: Int = 0,
        val omittedLines: Int = 0, val shortenedSources: Int = 0,
        val finishedAt: Long = 0,
        val streamDeleting: Boolean = false,
        val duplicateCopies: Int = 0, val memoryEvicted: Long = 0,
        val preparationFailed: Boolean = false,
    )
    private data class Pending(val config: RemoteLogConfig, val report: RemoteLogReport,
                               var next: Int = 0, var uploadedLines: Int = 0)
    @Volatile var status = Status()
        private set
    @Volatile private var generation = 0L
    private var transport: RemoteLogTransport? = null
    private var pending: Pending? = null
    // 取消后至多等待一个新任务，旧整理在检查点退出；反复点击不会堆叠整理线程。
    private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(1),
        { job -> Thread(job, "l7-manual-log-upload").apply { isDaemon = true } }, ThreadPoolExecutor.DiscardOldestPolicy())

    @Synchronized fun start(context: Context, retry: Boolean = false): Boolean {
        val app = context.applicationContext
        val saved = RemoteLogConfig.load(app)
        if (!saved.valid() || !allowed(app) || L7ProbeRunner.clearing || status.phase == Phase.UPLOADING) return false
        val config = saved.forDevice(RemoteLogDevice.id(app))
        val retained = if (retry) pending?.takeIf { it.config == config } else null
        if (retained == null) { pending?.report?.close(); pending = null }
        val run = ++generation
        val sender = RemoteLogTransport().also { transport = it }
        status = retained?.state(Phase.UPLOADING) ?: Status(Phase.UPLOADING)
        worker.execute { upload(app, config, retained, run, sender) }
        return true
    }

    private fun upload(app: Context, config: RemoteLogConfig, retained: Pending?, run: Long, sender: RemoteLogTransport) {
        var task = retained
        val code = runCatching {
            check(run == generation)
            val current = task ?: Pending(config, RemoteLogArchive.collect(app) {
                check(run == generation && !L7AppExit.exiting && !L7ProbeRunner.clearing)
            }).also { task = it }
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
                val phase = when {
                    task?.report?.batches?.isEmpty() == true -> Phase.EMPTY
                    code in 200..299 -> Phase.SUCCESS
                    else -> Phase.FAILED
                }
                var finished = (task?.state(phase, code) ?: Status(phase, code = code)).copy(
                    streamDeleting = sender.streamDeleting, preparationFailed = task == null)
                if (phase == Phase.EMPTY) { task?.report?.close(); pending = null }
                if (phase == Phase.SUCCESS) {
                    finished = finished.copy(finishedAt = System.currentTimeMillis())
                    runCatching { RemoteLogHistory.save(app, RemoteLogHistory.Entry(finished.finishedAt, finished.totalLines)) }
                    task?.report?.close()
                    pending = null
                }
                // 清理结束后再发布完成状态，避免界面已成功而临时快照仍在释放。
                status = finished
            } else task?.report?.close()
        }
    }

    private fun sendBatches(app: Context, task: Pending, run: Long, sender: RemoteLogTransport): Int {
        while (task.next < task.report.batches.size) {
            synchronized(this) { check(run == generation && allowed(app)) }
            val batch = task.report.batches[task.next]
            val code = sender.send(task.config, batch)
            if (code !in 200..299) return code
            synchronized(this) {
                check(run == generation && allowed(app))
                task.next++
                task.uploadedLines += batch.lineCount
                status = task.state(Phase.UPLOADING)
            }
            if (task.next < task.report.batches.size) Thread.sleep(100)
        }
        return 200
    }

    private fun Pending.state(phase: Phase, code: Int = 0) = Status(
        phase, report.id, code, next, report.batches.size,
        uploadedLines, report.lineCount, report.omittedLines, report.shortenedSources,
        duplicateCopies = report.duplicateCopies, memoryEvicted = report.memoryEvicted,
    )
    private fun allowed(context: Context) = L7Agreement.accepted(context) && !L7AppExit.exiting

    @Synchronized fun cancel() {
        generation++
        worker.queue.clear()
        // UI 不等待 socket 释放；代次检查阻止旧任务继续发送下一批或覆盖新状态。
        val old = transport
        transport = null
        if (old != null) Thread({ old.close() }, "l7-log-upload-cancel").apply { isDaemon = true; start() }
        pending?.report?.close(); pending = null
        status = status.copy(phase = Phase.CANCELLED)
    }
}
