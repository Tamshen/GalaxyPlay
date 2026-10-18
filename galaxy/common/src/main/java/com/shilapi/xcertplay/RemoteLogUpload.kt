package com.shilapi.xcertplay

import android.content.Context
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** 单个手动任务有界并发发送各批；重试跳过已确认批次，保留未确认批次的事件编号。 */
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
        val preparing: Boolean = false, val scannedLines: Int = 0,
        val remark: String = "", val stream: String = "",
    )
    private data class Pending(val config: RemoteLogConfig, val report: RemoteLogReport, val request: RemoteLogUploadRequest,
                               val confirmed: BooleanArray = BooleanArray(report.batches.size),
                               var completed: Int = 0, var uploadedLines: Int = 0)
    @Volatile var status = Status()
        private set
    @Volatile private var generation = 0L
    private var transport: RemoteLogBatchSender? = null
    private var pending: Pending? = null
    private var preparation: Pair<RemoteLogConfig, RemoteLogUploadRequest>? = null
    // 取消后至多等待一个新任务，旧整理在检查点退出；反复点击不会堆叠整理线程。
    private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(1),
        { job -> Thread(job, "l7-manual-log-upload").apply { isDaemon = true } }, ThreadPoolExecutor.DiscardOldestPolicy())

    @Synchronized fun start(context: Context, retry: Boolean = false, remark: String = ""): Boolean {
        val app = context.applicationContext
        val saved = RemoteLogConfig.load(app)
        if (!saved.valid() || !allowed(app) || L7ProbeRunner.clearing || status.phase == Phase.UPLOADING) return false
        val config = saved.forDevice(RemoteLogDevice.id(app))
        val retained = if (retry) pending?.takeIf { it.config == config } else null
        val request = retained?.request ?: (if (retry) preparation?.takeIf { it.first == config }?.second else null)
            ?: RemoteLogUploadRequest.create(remark)
        preparation = config to request
        if (retained == null) { pending?.report?.close(); pending = null }
        val run = ++generation
        val sender = RemoteLogBatchSender().also { transport = it }
        status = retained?.state(Phase.UPLOADING) ?: Status(Phase.UPLOADING, id = request.id, preparing = true,
            remark = request.remark, stream = config.stream)
        worker.execute { upload(app, config, retained, request, run, sender) }
        return true
    }

    private fun upload(app: Context, config: RemoteLogConfig, retained: Pending?, request: RemoteLogUploadRequest,
                       run: Long, sender: RemoteLogBatchSender) {
        var task = retained
        val code = runCatching {
            check(run == generation)
            val current = task ?: Pending(config, RemoteLogArchive.collect(app, request = request, progress = { scanned ->
                synchronized(this) {
                    if (run == generation && status.preparing) status = status.copy(scannedLines = scanned)
                }
            }) {
                check(run == generation && !L7AppExit.exiting && !L7ProbeRunner.clearing)
            }, request).also { task = it }
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
                var finished = (task?.state(phase, code) ?: Status(phase, id = request.id, code = code,
                    remark = request.remark, stream = config.stream)).copy(
                    streamDeleting = sender.streamDeleting, preparationFailed = task == null)
                if (phase == Phase.EMPTY) { task?.report?.close(); pending = null }
                if (phase == Phase.SUCCESS) {
                    finished = finished.copy(finishedAt = System.currentTimeMillis())
                    runCatching { RemoteLogHistory.save(app, RemoteLogHistory.Entry(finished.finishedAt,
                        finished.totalLines, finished.id, finished.remark, finished.stream)) }
                    task?.report?.close()
                    pending = null
                }
                if (phase in listOf(Phase.SUCCESS, Phase.EMPTY)) preparation = null
                // 清理结束后再发布完成状态，避免界面已成功而临时快照仍在释放。
                status = finished
            } else task?.report?.close()
        }
    }

    private fun sendBatches(app: Context, task: Pending, run: Long, sender: RemoteLogBatchSender): Int =
        sender.send(task.config, task.report.batches, task.confirmed,
            checkpoint = { check(run == generation && allowed(app) && !L7ProbeRunner.clearing) }) { index ->
            synchronized(this) {
                check(run == generation && allowed(app))
                check(!task.confirmed[index])
                task.confirmed[index] = true
                task.completed++
                task.uploadedLines += task.report.batches[index].lineCount
                status = task.state(Phase.UPLOADING)
            }
        }

    private fun Pending.state(phase: Phase, code: Int = 0) = Status(
        phase, report.id, code, completed, report.batches.size,
        uploadedLines, report.lineCount, report.omittedLines, report.shortenedSources,
        duplicateCopies = report.duplicateCopies, memoryEvicted = report.memoryEvicted,
        remark = request.remark, stream = config.stream,
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
        preparation = null
        status = status.copy(phase = Phase.CANCELLED, preparing = false)
    }
}
