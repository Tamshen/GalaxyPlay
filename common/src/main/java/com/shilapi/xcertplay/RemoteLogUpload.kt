package com.shilapi.xcertplay

import android.content.Context

/** 进程内只允许一个手动上传；重试保留事件编号，便于查询识别重复记录。 */
internal object RemoteLogUpload {
    enum class Phase { IDLE, UPLOADING, SUCCESS, FAILED, CANCELLED }
    data class Status(val phase: Phase = Phase.IDLE, val id: String = "", val code: Int = 0)
    @Volatile var status = Status()
        private set
    private var generation = 0L
    private var transport: RemoteLogTransport? = null
    private var pending: Pair<RemoteLogConfig, RemoteLogReport>? = null

    @Synchronized fun start(context: Context, retry: Boolean = false): Boolean {
        val app = context.applicationContext
        val config = RemoteLogConfig.load(app)
        if (!config.valid() || !L7Agreement.accepted(app) || L7AppExit.exiting || status.phase == Phase.UPLOADING) return false
        val retained = if (retry) pending?.takeIf { it.first == config }?.second else null
        val run = ++generation
        val sender = RemoteLogTransport().also { transport = it }
        status = Status(Phase.UPLOADING)
        Thread({
            var report = retained
            val code = runCatching {
                if (report == null) report = RemoteLogReport.collect(app)
                synchronized(this) {
                    check(run == generation && L7Agreement.accepted(app) && !L7AppExit.exiting)
                    pending = config to report!!
                }
                sender.send(config, report!!)
            }.getOrDefault(0)
            sender.close()
            synchronized(this) {
                if (run == generation) {
                    transport = null
                    status = Status(if (code in 200..299) Phase.SUCCESS else Phase.FAILED, report?.id.orEmpty(), code)
                    if (code in 200..299) pending = null
                }
            }
        }, "l7-manual-log-upload").apply { isDaemon = true; start() }
        return true
    }

    @Synchronized fun cancel() {
        generation++
        // 断开网络放到后台；设置操作、撤回协议和退出不等待 socket 释放。
        val old = transport
        transport = null
        if (old != null) Thread({ old.close() }, "l7-log-upload-cancel").apply { isDaemon = true; start() }
        pending = null
        status = Status(Phase.CANCELLED)
    }
}
