package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.host.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import java.util.UUID

internal data class RemoteLogEntry(val index: Int, val source: String, val message: String,
    val configurationId: String? = null, val configuration: GalaxyConfigurationEvidence? = null,
    val configurationRole: String? = null)

/** 只在发送当前批次时生成 JSON，避免同时保留全部请求体。 */
internal class RemoteLogBatch(
    private val metadata: RemoteLogMetadata,
    val index: Int,
    val total: Int,
    entries: List<RemoteLogEntry>,
    private val loadEntries: (() -> List<RemoteLogEntry>)? = null,
    private val storedCount: Int? = null,
    private val loadBody: (() -> ByteArray)? = null,
) {
    private val initialEntries = entries
    val entries get() = loadEntries?.invoke() ?: initialEntries
    val lineCount get() = storedCount ?: entries.size
    val body: ByteArray get() = (loadBody?.invoke() ?: JSONArray(entries.map { metadata.event(it, index, total) })
        .toString().toByteArray(Charsets.UTF_8)).also { check(it.size <= RemoteLogReport.MAX_BODY) }
}

internal data class RemoteLogMetadata(val id: String, val collected: String, val version: String, val core: String,
    val remark: String = "") {
    fun event(entry: RemoteLogEntry, batch: Int, total: Int): JSONObject = JSONObject()
        .put("report_id", id).put("event_id", "$id:${entry.index}").put("collected_at", collected)
        .put("upload_remark", remark)
        .put("app_version", version).put("core_version", core).put("line_index", entry.index)
        .put("source", entry.source).put("batch_index", batch).put("batch_count", total).put("message", entry.message)
        .put("config_id", entry.configurationId ?: "unknown")
        .also { event -> entry.configuration?.let { evidence ->
            event.put("configuration_role", entry.configurationRole).put("configuration_file_name", "${evidence.id}.json")
                .put("configuration_file", JSONObject(evidence.text))
        } }
}

/** 共用批次元数据；完整上传用磁盘快照，查看器保留近期窗口。设备只由 URL 区分。 */
internal data class RemoteLogReport(
    val id: String,
    val batches: List<RemoteLogBatch>,
    val omittedLines: Int,
    val shortenedSources: Int,
    val duplicateCopies: Int = 0,
    val memoryEvicted: Long = 0,
    private val storage: java.io.Closeable? = null,
    private val storedLineCount: Int? = null,
) : java.io.Closeable {
    val lineCount get() = storedLineCount ?: batches.sumOf { it.lineCount }
    override fun close() { storage?.close() }

    companion object {
        const val MAX_BODY = 256 * 1024
        const val MAX_BATCH_LINES = 1000
        const val MAX_REPORT_BODY = 1024 * 1024
        private const val SUMMARY_RESERVE = 4096
        const val MAX_LINES = 5000
        private val configurationReference = Regex("(?:^| )config_ref=(cfg_[g-v]{64})(?: |$)")
        internal fun redact(line: String): String? = DiagnosticRedactor.redact(line)

        // 查看器使用近期窗口；完整上传必须调用 RemoteLogArchive，不能经过候选裁剪。
        fun collect(context: Context, checkpoint: () -> Unit = {}): RemoteLogReport {
            checkpoint()
            val drained = AsyncDiagnosticLog.awaitIdle(500)
            checkpoint()
            val buffer = L7DebugLog.buffer.snapshot()
            var shortened = 0
            val lines = sequence {
                for (name in SessionLogFile.REPORT_NAMES + "memory" + L7ProbeLog.files) {
                    checkpoint()
                    if (name == "memory") {
                        for (line in buffer.lines) yield("memory" to line)
                        continue
                    }
                    val limit = if (name in L7ProbeLog.files) L7ProbeLog.MAX_BYTES.toLong() else SessionLogFile.MAX_BYTES
                    val file = File(context.filesDir, "logs/$name")
                    if (!file.isFile) continue
                    val result = runCatching { tail(file, limit) { shortened++ } }
                    checkpoint()
                    if (result.isFailure) {
                        yield("collection" to "read_failed source=$name error=${result.exceptionOrNull()!!.javaClass.simpleName}")
                        continue
                    }
                    for (line in result.getOrThrow().lineSequence()) yield(name to line)
                }
                // 位于采集尾部，连续两次失败摘要不会被大量媒体／探测记录挤掉。
                val wired = L7WiredDiagnostics.report(context)
                if (wired.isNotEmpty()) {
                    wired.forEach { yield("wired-startup" to it) }
                    ProcessExitDiagnostics.report(context).lineSequence().forEach { yield("process-exits" to it) }
                }
            }
            val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
            return build(lines, version, context.getString(R.string.l7_core_source_info),
                "snapshot writerDrained=$drained memoryEvicted=${buffer.evicted}",
                runCatching { GalaxyConfigurationEvidence.capture(context) }.getOrNull(), checkpoint, buffer.evicted) { shortened }
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

        internal fun create(lines: Sequence<String>, version: String, core: String,
                            checkpoint: () -> Unit): RemoteLogReport =
            build(lines.map { "runtime" to it }, version, core, checkpoint = checkpoint) { 0 }

        private fun build(lines: Sequence<Pair<String, String>>, version: String, core: String,
                          snapshot: String? = null, saved: GalaxyConfigurationEvidence? = null,
                          checkpoint: () -> Unit = {},
                          memoryEvicted: Long = 0,
                          shortened: () -> Int): RemoteLogReport {
            val metadata = RemoteLogMetadata(UUID.randomUUID().toString(), Instant.now().toString(), version.take(80), core.take(120))
            val retained = RemoteLogWindow<RemoteLogEntry>(MAX_LINES, MAX_REPORT_BODY - SUMMARY_RESERVE, deduplicate = false)
            val configurations = linkedMapOf<String, GalaxyConfigurationEvidence>()
            val raw = RemoteLogSnapshot()
            for ((source, line) in lines) {
                checkpoint()
                raw.append(source, line)
            }
            for (header in raw.headers) {
                checkpoint()
                runCatching { GalaxyConfigurationEvidence.read(header) }
                    .onSuccess { configurations[it.id] = it }
            }
            val candidates = raw.lines
            val prepared = candidates.size
            var omitted = raw.omitted; var excluded = 0; var masked = 0; var truncated = 0; var count = raw.count
            while (candidates.isNotEmpty()) {
                checkpoint()
                val (index, source, line) = candidates.removeFirst()
                val safe = redact(line)
                if (safe == null) { excluded++; continue }
                if (safe != line || "[redacted]" in safe) masked++
                if (safe.endsWith(" [truncated]")) truncated++
                val reference = configurationReference.find(safe)?.groupValues?.get(1)
                val entry = RemoteLogEntry(index, source, safe, reference)
                val size = metadata.event(entry, MAX_LINES + 1, MAX_LINES + 1).toString().toByteArray(Charsets.UTF_8).size + 1
                omitted += retained.add(index, source, safe, entry, size)
            }
            // 无实际内容时不为了摘要发请求，也不能记录为上传成功。
            if (retained.size == 0) return RemoteLogReport(metadata.id, emptyList(), omitted, shortened(), raw.duplicateCopies, memoryEvicted)
            fun headers(): List<RemoteLogEntry> {
                val referenced = retained.ordered().mapNotNull { it.value.configurationId }.distinct()
                val historical = referenced.mapNotNull { configurations[it] }.mapIndexed { index, evidence ->
                    RemoteLogEntry(count + index, "configuration", "configuration_snapshot", evidence.id, evidence, "at_event")
                }
                return historical + listOfNotNull(saved?.let { evidence ->
                    RemoteLogEntry(count + historical.size, "configuration", "saved_configuration_at_upload", evidence.id, evidence, "saved_at_upload")
                })
            }
            val references = retained.ordered().mapNotNull { it.value.configurationId }.groupingBy { it }.eachCount().toMutableMap()
            val costs = mutableMapOf<String, Int>()
            // 当前保存副本与历史同 ID 时仍是两个角色，单独计量；裁剪过程不反复序列化配置。
            val savedCost = saved?.let { evidence -> metadata.event(RemoteLogEntry(count + 64, "configuration",
                "saved_configuration_at_upload", evidence.id, evidence, "saved_at_upload"),
                MAX_LINES + 1, MAX_LINES + 1).toString().toByteArray().size + 33 } ?: 0
            configurations.forEach { (id, evidence) ->
                checkpoint()
                costs[id] = metadata.event(RemoteLogEntry(count + 64,
                    "configuration", "configuration_snapshot", id, evidence, "at_event"), MAX_LINES + 1,
                    MAX_LINES + 1).toString().toByteArray().size + 33
            }
            var extraBytes = savedCost + references.keys.sumOf { costs[it] ?: 0 }
            while (retained.size > 0 && retained.cost + extraBytes > MAX_REPORT_BODY - SUMMARY_RESERVE) {
                checkpoint()
                val removed = retained.removeOne()
                omitted++
                removed.value.configurationId?.let { id ->
                    val remaining = references.getValue(id) - 1
                    if (remaining == 0) { references.remove(id); extraBytes -= costs[id] ?: 0 }
                    else references[id] = remaining
                }
            }
            val headers = headers()
            val missing = references.keys.count { it !in configurations }
            count += headers.size
            val notes = snapshot?.let { listOf(RemoteLogEntry(count++, "collection", it)) }.orEmpty()
            val summary = RemoteLogEntry(count, "collection", "upload_summary retained=${retained.size} " +
                "scannedLines=${raw.count} preparedLines=$prepared " +
                "omittedLines=$omitted duplicateCopies=${raw.duplicateCopies} memoryEvicted=$memoryEvicted " +
                "excludedPayloadLines=$excluded redactedLines=$masked truncatedLines=$truncated " +
                "shortenedSources=${shortened()} missingConfigurations=$missing currentConfigurationAvailable=${saved != null}")
            val ordered = retained.ordered()
            val sizes = ordered.associate { it.value.index to it.cost - 1 }
            val groups = split(headers + ordered.map { it.value } + notes + summary, metadata, sizes, checkpoint)
            return RemoteLogReport(metadata.id, groups.mapIndexed { index, entries ->
                RemoteLogBatch(metadata, index + 1, groups.size, entries)
            }, omitted, shortened(), raw.duplicateCopies, memoryEvicted)
        }

        private fun split(entries: Iterable<RemoteLogEntry>, metadata: RemoteLogMetadata,
                          sizes: Map<Int, Int>, checkpoint: () -> Unit): List<List<RemoteLogEntry>> {
            val result = mutableListOf<List<RemoteLogEntry>>()
            var batch = mutableListOf<RemoteLogEntry>()
            var bytes = 2
            for (entry in entries) {
                checkpoint()
                // 用批次编号的上界计量 UTF-8 JSON，实际生成时不会超过单批预算。
                val size = sizes[entry.index] ?: metadata.event(entry, MAX_LINES + 1, MAX_LINES + 1)
                    .toString().toByteArray(Charsets.UTF_8).size
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
