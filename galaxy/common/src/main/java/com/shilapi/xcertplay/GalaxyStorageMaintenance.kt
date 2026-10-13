package com.shilapi.xcertplay

import android.content.Context
import java.io.File
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** 单个低频维护线程，不在 UI 扫描目录；日志写入时的预算核对使用现有写盘线程。 */
internal object GalaxyStorageMaintenance {
    internal val worker = ScheduledThreadPoolExecutor(1) { job ->
        Thread(job, "GalaxyPlay-storage").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    private var installed = false

    @Synchronized fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val retention = GalaxyLogRetention(File(app.filesDir, "logs"))
        SessionLogFile.storageChanged = retention::changed
        worker.scheduleWithFixedDelay({
            runCatching { retention.prune() }
            runCatching { GalaxyTemporaryFiles.cleanup(app) }
        }, 0, 60, TimeUnit.SECONDS)
    }
}
