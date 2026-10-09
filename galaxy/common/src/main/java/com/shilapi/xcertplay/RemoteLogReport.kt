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
        .put("config_id", entry.configurationId ?: "unknown")
        .also { event -> entry.configuration?.let { evidence ->
            event.put("configuration_role", entry.configurationRole).put("configuration_file_name", "${evidence.id}.json")
                .put("configuration_file", JSONObject(evidence.text))
        } }
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
                runCatching { GalaxyConfigurationEvidence.capture(context) }.getOrNull()) { shortened }
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

        private fun build(lines: Sequence<Pair<String, String>>, version: String, core: String,
                          snapshot: String? = null, saved: GalaxyConfigurationEvidence? = null,
                          shortened: () -> Int): RemoteLogReport {
            val metadata = RemoteLogMetadata(UUID.randomUUID().toString(), Instant.now().toString(), version.take(80), core.take(120))
            val retained = ArrayDeque<Pair<RemoteLogEntry, Int>>()
            val configurations = linkedMapOf<String, GalaxyConfigurationEvidence>()
            var retainedBytes = 0
            var omitted = 0; var excluded = 0; var masked = 0; var truncated = 0; var count = 0
            for ((source, line) in lines) {
                val index = count++
                if (line.isBlank()) continue
                if (line.startsWith(GalaxyConfigurationEvidence.PREFIX)) {
                    runCatching { GalaxyConfigurationEvidence.read(line.removePrefix(GalaxyConfigurationEvidence.PREFIX)) }
                        .onSuccess { evidence ->
                            configurations[evidence.id] = evidence
                            // 日志文件最多数十份，超预算旧头明确视为证据不可用，不归到当前配置。
                            if (configurations.size > 64) configurations.remove(configurations.keys.first())
                        }
                    continue
                }
                val safe = redact(line)
                if (safe == null) { excluded++; continue }
                if (safe != line || "[redacted]" in safe) masked++
                if (safe.endsWith(" [truncated]")) truncated++
                val reference = Regex("(?:^| )config_ref=(cfg_[g-v]{64})(?: |$)").find(safe)?.groupValues?.get(1)
                val entry = RemoteLogEntry(index, source, safe, reference)
                val size = metadata.event(entry, MAX_LINES + 1, MAX_LINES + 1).toString().toByteArray(Charsets.UTF_8).size + 1
                while (retained.isNotEmpty() && (retained.size == MAX_LINES || retainedBytes + size > MAX_REPORT_BODY - SUMMARY_RESERVE)) {
                    retainedBytes -= retained.removeFirst().second
                    omitted++
                }
                // 不按文本去重：相同报错的重复次数和先后顺序也是诊断证据。
                retained += entry to size
                retainedBytes += size
            }
            // 无实际内容时不为了摘要发请求，也不能记录为上传成功。
            if (retained.isEmpty()) return RemoteLogReport(metadata.id, emptyList(), omitted, shortened())
            fun headers(): List<RemoteLogEntry> {
                val referenced = retained.mapNotNull { it.first.configurationId }.distinct()
                val historical = referenced.mapNotNull { configurations[it] }.mapIndexed { index, evidence ->
                    RemoteLogEntry(count + index, "configuration", "configuration_snapshot", evidence.id, evidence, "at_event")
                }
                return historical + listOfNotNull(saved?.let { evidence ->
                    RemoteLogEntry(count + historical.size, "configuration", "saved_configuration_at_upload", evidence.id, evidence, "saved_at_upload")
                })
            }
            val references = retained.mapNotNull { it.first.configurationId }.groupingBy { it }.eachCount().toMutableMap()
            val costs = headers().associate { it.configurationId!! to
                (metadata.event(it, MAX_LINES + 1, MAX_LINES + 1).toString().toByteArray().size + 33) }.toMutableMap()
            // 当前保存副本与历史同 ID 时仍是两个角色，单独计量；裁剪过程不反复序列化配置。
            val savedCost = saved?.let { evidence -> metadata.event(RemoteLogEntry(count + 64, "configuration",
                "saved_configuration_at_upload", evidence.id, evidence, "saved_at_upload"),
                MAX_LINES + 1, MAX_LINES + 1).toString().toByteArray().size + 33 } ?: 0
            configurations.forEach { (id, evidence) -> costs[id] = metadata.event(RemoteLogEntry(count + 64,
                "configuration", "configuration_snapshot", id, evidence, "at_event"), MAX_LINES + 1,
                MAX_LINES + 1).toString().toByteArray().size + 33 }
            var extraBytes = savedCost + references.keys.sumOf { costs[it] ?: 0 }
            while (retained.isNotEmpty() && retainedBytes + extraBytes > MAX_REPORT_BODY - SUMMARY_RESERVE) {
                val removed = retained.removeFirst()
                retainedBytes -= removed.second; omitted++
                removed.first.configurationId?.let { id ->
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
                "omittedLines=$omitted excludedPayloadLines=$excluded redactedLines=$masked truncatedLines=$truncated " +
                "shortenedSources=${shortened()} missingConfigurations=$missing currentConfigurationAvailable=${saved != null}")
            val groups = split(headers + retained.map { it.first } + notes + summary, metadata)
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
