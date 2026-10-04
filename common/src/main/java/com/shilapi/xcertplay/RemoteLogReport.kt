package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.host.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import java.util.UUID

/** 只收集有界文本日志，不上传完整设备报告、认证材料或固定设备标识。 */
internal data class RemoteLogReport(val id: String, val body: ByteArray, val lineCount: Int) {
    companion object {
        const val MAX_BODY = 256 * 1024
        private val privateFields = Regex("(?i)authorization|cookie|bearer |fingerprint|serial|imei|android.?id|\\bvin[=:]|https?://|(?:/Users/|/storage/|/data/)|bluetooth.*name|(?:phone|device|peer)[=:]")

        internal fun redact(line: String): String? =
            if (privateFields.containsMatchIn(line) || line.any { it.code < 32 && it != '\t' || it.code == 127 }) null
            else DiagnosticRedactor.redact(line)

        fun collect(context: Context): RemoteLogReport {
            AsyncDiagnosticLog.awaitIdle(500)
            val lines = ArrayList<String>()
            for (name in listOf("previous.log", "diplay.log")) {
                val file = File(context.filesDir, "logs/$name")
                if (file.isFile) runCatching {
                    RandomAccessFile(file, "r").use { input ->
                        val offset = (input.length() - SessionLogFile.MAX_BYTES).coerceAtLeast(0)
                        input.seek(offset)
                        val bytes = ByteArray((input.length() - offset).toInt())
                        input.readFully(bytes)
                        val text = bytes.toString(Charsets.UTF_8)
                        lines += (if (offset > 0) text.substringAfter('\n', "") else text).lineSequence().toList()
                    }
                }
            }
            lines += L7DebugLog.buffer.snapshot().lines
            val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
            return create(lines, version, context.getString(R.string.l7_core_source_info))
        }

        internal fun create(lines: List<String>, version: String, core: String): RemoteLogReport {
            // 每条日志一个 OpenObserve 事件；按实际 JSON 字节预算保留最近记录。
            val id = UUID.randomUUID().toString()
            val collected = Instant.now().toString()
            val selected = ArrayDeque<JSONObject>()
            val seen = HashSet<String>()
            var bytes = 2
            for ((index, line) in lines.withIndex().reversed()) {
                val safe = redact(line)?.takeIf { it.isNotBlank() } ?: continue
                if (!seen.add(safe)) continue
                // _timestamp 由服务端填接收时间，避免车机时钟异常使日志落到错误的查询时间段。
                val event = JSONObject().put("report_id", id).put("event_id", "$id:$index")
                    .put("collected_at", collected).put("app_version", version.take(80))
                    .put("core_version", core.take(120)).put("line_index", index).put("message", safe)
                val size = event.toString().toByteArray(Charsets.UTF_8).size + 1
                if (selected.size == 1000 || bytes + size > MAX_BODY) break
                selected.addFirst(event)
                bytes += size
            }
            if (selected.isEmpty()) {
                selected.add(JSONObject().put("report_id", id).put("event_id", "$id:empty")
                    .put("collected_at", collected).put("app_version", version.take(80))
                    .put("core_version", core.take(120)).put("line_index", 0).put("message", "No diagnostic lines available"))
            }
            val body = JSONArray(selected.toList()).toString().toByteArray(Charsets.UTF_8)
            check(body.size <= MAX_BODY)
            return RemoteLogReport(id, body, selected.size)
        }
    }
}
