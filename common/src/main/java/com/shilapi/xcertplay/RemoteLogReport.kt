package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.host.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import java.util.UUID

internal data class RemoteLogEntry(val index: Int, val source: String, val message: String)

/** 只在发送当前批次时生成 JSON，避免同时保留全部请求体。 */
internal class RemoteLogBatch(
    private val metadata: RemoteLogMetadata,
    val index: Int,
    val total: Int,
    val entries: List<RemoteLogEntry>,
) {
    val lineCount get() = entries.size
    val body: ByteArray get() = JSONArray(entries.map { metadata.event(it, index, total) })
        .toString().toByteArray(Charsets.UTF_8).also { check(it.size <= RemoteLogReport.MAX_BODY) }
}

internal data class RemoteLogMetadata(val id: String, val collected: String, val version: String, val core: String) {
    fun event(entry: RemoteLogEntry, batch: Int, total: Int): JSONObject = JSONObject()
        .put("report_id", id).put("event_id", "$id:${entry.index}").put("collected_at", collected)
        .put("app_version", version).put("core_version", core).put("line_index", entry.index)
        .put("source", entry.source).put("batch_index", batch).put("batch_count", total).put("message", entry.message)
}

/** 每次点击冻结一份有界快照；设备只由 URL 区分，正文不包含硬件编号。 */
internal data class RemoteLogReport(
    val id: String,
    val batches: List<RemoteLogBatch>,
    val omittedLines: Int,
    val shortenedSources: Int,
) {
    val lineCount get() = batches.sumOf { it.lineCount }

    companion object {
        const val MAX_BODY = 256 * 1024
        const val MAX_BATCH_LINES = 1000
        const val MAX_REPORT_BODY = 1024 * 1024
        private const val SUMMARY_RESERVE = 4096
        const val MAX_LINES = 5000
        internal fun redact(line: String): String? = DiagnosticRedactor.redact(line)

        fun collect(context: Context): RemoteLogReport {
            val drained = AsyncDiagnosticLog.awaitIdle(500)
            val buffer = L7DebugLog.buffer.snapshot()
            var shortened = 0
            val lines = sequence {
                for (name in SessionLogFile.REPORT_NAMES + "memory" + L7ProbeLog.files) {
                    if (name == "memory") {
                        for (line in buffer.lines) yield("memory" to line)
                        continue
                    }
                    val limit = if (name in L7ProbeLog.files) L7ProbeLog.MAX_BYTES.toLong() else SessionLogFile.MAX_BYTES
                    val file = File(context.filesDir, "logs/$name")
                    if (!file.isFile) continue
                    val result = runCatching { tail(file, limit) { shortened++ } }
                    if (result.isFailure) {
                        yield("collection" to "read_failed source=$name error=${result.exceptionOrNull()!!.javaClass.simpleName}")
                        continue
                    }
                    for (line in result.getOrThrow().lineSequence()) yield(name to line)
                }
                yield("collection" to "snapshot writerDrained=$drained memoryEvicted=${buffer.evicted}")
            }
            val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
            return build(lines, version, context.getString(R.string.l7_core_source_info)) { shortened }
        }

        private fun tail(file: File, limit: Long, onShortened: () -> Unit): String = RandomAccessFile(file, "r").use {
            val length = it.length()
            val offset = (length - limit).coerceAtLeast(0)
            it.seek(offset)
            val bytes = ByteArray((length - offset).toInt())
            it.readFully(bytes)
            val text = bytes.toString(Charsets.UTF_8)
            if (offset == 0L) text else { onShortened(); text.substringAfter('\n', "") }
        }

        internal fun create(lines: List<String>, version: String, core: String): RemoteLogReport =
            build(lines.asSequence().map { "runtime" to it }, version, core) { 0 }

        private fun build(lines: Sequence<Pair<String, String>>, version: String, core: String, shortened: () -> Int): RemoteLogReport {
            val metadata = RemoteLogMetadata(UUID.randomUUID().toString(), Instant.now().toString(), version.take(80), core.take(120))
            val retained = ArrayDeque<Pair<RemoteLogEntry, Int>>()
            var retainedBytes = 0
            var omitted = 0; var excluded = 0; var masked = 0; var truncated = 0; var count = 0
            for ((source, line) in lines) {
                val index = count++
                if (line.isBlank()) continue
                val safe = redact(line)
                if (safe == null) { excluded++; continue }
                if (safe != line || "[redacted]" in safe) masked++
                if (safe.endsWith(" [truncated]")) truncated++
                val entry = RemoteLogEntry(index, source, safe)
                val size = metadata.event(entry, MAX_LINES + 1, MAX_LINES + 1).toString().toByteArray(Charsets.UTF_8).size + 1
                while (retained.isNotEmpty() && (retained.size == MAX_LINES || retainedBytes + size > MAX_REPORT_BODY - SUMMARY_RESERVE)) {
                    retainedBytes -= retained.removeFirst().second
                    omitted++
                }
                // 不按文本去重：相同报错的重复次数和先后顺序也是诊断证据。
                retained += entry to size
                retainedBytes += size
            }
            val summary = RemoteLogEntry(count, "collection", "upload_summary retained=${retained.size} " +
                "omittedLines=$omitted excludedPayloadLines=$excluded redactedLines=$masked truncatedLines=$truncated shortenedSources=${shortened()}")
            val groups = split(retained.map { it.first } + summary, metadata)
            return RemoteLogReport(metadata.id, groups.mapIndexed { index, entries ->
                RemoteLogBatch(metadata, index + 1, groups.size, entries)
            }, omitted, shortened())
        }

        private fun split(entries: Iterable<RemoteLogEntry>, metadata: RemoteLogMetadata): List<List<RemoteLogEntry>> {
            val result = mutableListOf<List<RemoteLogEntry>>()
            var batch = mutableListOf<RemoteLogEntry>()
            var bytes = 2
            for (entry in entries) {
                // 用批次编号的上界计量 UTF-8 JSON，实际生成时不会超过单批预算。
                val size = metadata.event(entry, MAX_LINES + 1, MAX_LINES + 1).toString().toByteArray(Charsets.UTF_8).size
                if (batch.isNotEmpty() && (batch.size == MAX_BATCH_LINES || bytes + size + 1 > MAX_BODY)) {
                    result += batch; batch = mutableListOf(); bytes = 2
                }
                check(size + 2 <= MAX_BODY)
                bytes += size + if (batch.isEmpty()) 0 else 1
                batch += entry
            }
            if (batch.isNotEmpty()) result += batch
            return result
        }
    }
}
