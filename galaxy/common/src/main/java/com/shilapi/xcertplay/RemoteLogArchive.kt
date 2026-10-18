package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.host.R
import java.io.File
import java.util.UUID

/** 上传保存完整的点击时快照，单批有界；不按总字节数或总条数丢弃上下文。 */
internal object RemoteLogArchive {
    fun collect(context: Context, progress: ((Int) -> Unit)? = null,
                request: RemoteLogUploadRequest = RemoteLogUploadRequest.create(),
                checkpoint: () -> Unit = {}): RemoteLogReport {
        val started = System.nanoTime()
        checkpoint()
        val root = File(context.cacheDir, "log-upload-snapshots").apply { mkdirs() }
        // 只有上传单 worker 调用；新任务不会保留上个进程或已放弃任务的临时快照。
        GalaxyTemporaryFiles.cleanup(context)
        val directory = File(root, "upload-${UUID.randomUUID()}")
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        val metadata = RemoteLogMetadata(request.id, request.created, version.take(80),
            context.getString(R.string.l7_core_source_info).take(120), request.remark)
        val store = RemoteLogBatchStore(directory, metadata, GalaxyTemporaryFiles.protect(directory))
        try {
            check(directory.mkdirs())
            val drained = AsyncDiagnosticLog.awaitIdle(500)
            checkpoint()
            val memory = L7DebugLog.buffer.snapshot()
            val input = File(directory, "input").apply { mkdirs() }
            val files = RemoteLogArchiveInput.freeze(context, input, checkpoint)
            val indexStarted = System.nanoTime()
            RemoteLogArchiveIndex(File(directory, "index.db")).use { index ->
                for (file in files) for (line in RemoteLogArchiveInput.lines(file, checkpoint)) {
                    checkpoint(); index.append(file.name, line)
                    if (index.scanned % 256 == 0) {
                        GalaxyTemporaryFiles.checkSnapshot(directory)
                        progress?.invoke(index.scanned)
                    }
                }
                memory.lines.forEach { checkpoint(); index.append("memory", it) }
                val wired = L7WiredDiagnostics.report(context)
                wired.forEach { checkpoint(); index.append("wired-startup", it) }
                if (wired.isNotEmpty()) ProcessExitDiagnostics.report(context).lineSequence().forEach {
                    checkpoint(); index.append("process-exits", it)
                }
                index.finish()
                val indexed = System.nanoTime()
                progress?.invoke(index.scanned)
                GalaxyTemporaryFiles.checkSnapshot(directory)
                if (index.retained == 0) {
                    store.close()
                    return RemoteLogReport(metadata.id, emptyList(), 0, 0)
                }
                write(store, metadata, records(context, index, request, drained, memory.evicted, checkpoint,
                    (indexStarted - started) / 1_000_000, (indexed - indexStarted) / 1_000_000), checkpoint)
                return RemoteLogReport(metadata.id, store.batches, 0, 0, index.duplicateCopies, memory.evicted,
                    store, store.lineCount)
            }
        } catch (error: Throwable) { store.close(); throw error }
        finally {
            File(directory, "input").deleteRecursively()
            directory.listFiles()?.filter { it.name.startsWith("index.db") }?.forEach { it.delete() }
        }
    }

    private fun records(context: Context, index: RemoteLogArchiveIndex, request: RemoteLogUploadRequest,
                        drained: Boolean, evicted: Long,
                        checkpoint: () -> Unit, freezeMillis: Long, indexMillis: Long): Sequence<RemoteLogEntry> = sequence {
        val scanned = index.scanned
        // 边界属于冻结报告；END 不代表所有批次已获服务端确认。
        yield(RemoteLogEntry(-1, "upload", request.boundary(begin = true)))
        yieldAll(index.configurations(checkpoint))
        val saved = runCatching { GalaxyConfigurationEvidence.capture(context) }.getOrNull()
        saved?.let {
            yield(RemoteLogEntry(index.scanned, "configuration", "saved_configuration_at_upload", saved.id, saved, "saved_at_upload"))
        }
        yieldAll(index.entries(checkpoint))
        yield(RemoteLogEntry(index.scanned + 1, "collection", "snapshot mode=full writerDrained=$drained memoryEvicted=$evicted"))
        yield(RemoteLogEntry(index.scanned + 2, "collection", "upload_summary mode=full retained=${index.retained} " +
            "scannedLines=$scanned omittedLines=0 shortenedSources=0 duplicateCopies=${index.duplicateCopies} " +
            "memoryEvicted=$evicted excludedPayloadLines=${index.excluded} truncatedLines=${index.truncated} " +
            "prepareFreezeMs=$freezeMillis prepareIndexMs=$indexMillis"))
        val total = index.retained + (index.scanned - scanned) + (if (saved == null) 0 else 1) + 4
        yield(RemoteLogEntry(index.scanned + 3, "upload", request.boundary(begin = false, records = total)))
    }

    private fun write(store: RemoteLogBatchStore, metadata: RemoteLogMetadata,
                      entries: Sequence<RemoteLogEntry>, checkpoint: () -> Unit) {
        var batch = mutableListOf<String>()
        var bytes = 2
        for (entry in entries) {
            checkpoint()
            // 用 Int 上界预留批次号位数；总量无截断，只有单请求字节／条数限制。
            val event = metadata.event(entry, Int.MAX_VALUE, Int.MAX_VALUE).toString()
            val size = event.toByteArray(Charsets.UTF_8).size
            check(size + 2 <= RemoteLogReport.MAX_BODY)
            if (batch.isNotEmpty() && (batch.size == RemoteLogReport.MAX_BATCH_LINES || bytes + size + 1 > RemoteLogReport.MAX_BODY)) {
                store.write(batch); GalaxyTemporaryFiles.checkSnapshot(store.directory)
                batch = mutableListOf(); bytes = 2
            }
            bytes += size + if (batch.isEmpty()) 0 else 1
            batch += event
        }
        if (batch.isNotEmpty()) { store.write(batch); GalaxyTemporaryFiles.checkSnapshot(store.directory) }
    }
}
