package com.shilapi.xcertplay

import java.io.Closeable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 单次手动上传最多三个请求在途；失败先收齐在途确认，停止派发后续批次。 */
internal class RemoteLogBatchSender : Closeable {
    private data class Result(val index: Int, val code: Int, val streamDeleting: Boolean)
    private val workers = Executors.newFixedThreadPool(PARALLELISM) { job ->
        Thread(job, "l7-log-upload-request").apply { isDaemon = true }
    }
    private val transports = mutableSetOf<RemoteLogTransport>()
    private var closed = false
    var streamDeleting = false
        private set

    fun send(config: RemoteLogConfig, batches: List<RemoteLogBatch>, confirmed: BooleanArray,
             checkpoint: () -> Unit, acknowledged: (Int) -> Unit): Int {
        val completions = ExecutorCompletionService<Result>(workers)
        var next = 0
        var active = 0
        var failure: Int? = null
        while (true) {
            checkpoint()
            while (failure == null && active < PARALLELISM && next < batches.size) {
                val index = next++
                if (confirmed[index]) continue
                checkpoint()
                completions.submit(java.util.concurrent.Callable { request(config, batches, index, checkpoint) })
                active++
            }
            if (active == 0) return failure ?: 200
            // 短轮询让取消能退出；取消的排队任务不一定产生完成事件，不能无限 take。
            val completed = completions.poll(100, TimeUnit.MILLISECONDS) ?: continue
            val result = completed.get()
            active--
            checkpoint()
            if (result.code in 200..299) acknowledged(result.index)
            else {
                if (failure == null) failure = result.code
                streamDeleting = streamDeleting || result.streamDeleting
            }
        }
    }

    private fun request(config: RemoteLogConfig, batches: List<RemoteLogBatch>, index: Int,
                        checkpoint: () -> Unit): Result {
        val transport = RemoteLogTransport()
        synchronized(this) {
            if (closed) return Result(index, 0, false)
            transports += transport
        }
        return try {
            val code = runCatching { checkpoint(); transport.send(config, batches[index]) }.getOrDefault(0)
            Result(index, code, transport.streamDeleting)
        } finally {
            transport.close()
            synchronized(this) { transports -= transport }
        }
    }

    override fun close() {
        val active = synchronized(this) { closed = true; transports.toList() }
        workers.shutdownNow()
        active.forEach { it.close() }
    }

    companion object { const val PARALLELISM = 3 }
}
