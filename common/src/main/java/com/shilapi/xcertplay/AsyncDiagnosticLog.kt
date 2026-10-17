package com.shilapi.xcertplay

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 队列只保存所属会话文件与已脱敏元数据；清空后拒绝旧代次尚未落盘的记录。 */
internal object AsyncDiagnosticLog {
    private data class Entry(val target: SessionLogFile, val line: String, val generation: Long,
        val configuration: GalaxyConfigurationEvidence?, val timestamp: Long, val event: String)
    @Volatile private var generation = 0L
    // 格式器与文件批次都仅由同一写入线程使用，生产线程只脱敏并入队。
    private val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val writer = BoundedDiagnosticWriter<Entry>(writeBatch = ::writeBatch) { writeBatch(listOf(it)) }

    private fun writeBatch(entries: List<Entry>) {
        synchronized(SessionLogFile.storageLock) {
            var index = 0
            while (index < entries.size) {
                val first = entries[index++]
                val group = mutableListOf(first)
                while (index < entries.size && entries[index].target === first.target &&
                    entries[index].configuration === first.configuration && entries[index].generation == first.generation)
                    group.add(entries[index++])
                if (first.generation != generation) continue
                first.target.appendRedactedBatch(group.map {
                    "${timestamp.format(Date(it.timestamp))}  ${DiagnosticLogIdentity.prefix(it.event, it.timestamp)}${it.line}"
                }, first.configuration)
            }
        }
    }

    fun append(target: SessionLogFile?, message: String, nowMillis: Long = System.currentTimeMillis(),
        configuration: GalaxyConfigurationEvidence? = target?.configuration,
        eventId: String = DiagnosticLogIdentity.next()) {
        if (target == null) return
        val captured = generation
        val safe = DiagnosticRedactor.redact(message) ?: return
        enqueue(target, safe, nowMillis, configuration, eventId, captured)
    }

    /** 仅供内部已脱敏正文使用；时间和事件标识仍由受控编码生成。 */
    internal fun appendRedacted(target: SessionLogFile?, safe: String, nowMillis: Long,
        configuration: GalaxyConfigurationEvidence?, eventId: String) {
        if (target == null) return
        enqueue(target, safe, nowMillis, configuration, eventId, generation)
    }
    private fun enqueue(target: SessionLogFile, safe: String, nowMillis: Long,
        configuration: GalaxyConfigurationEvidence?, eventId: String, captured: Long) {
        runCatching { writer.enqueue(Entry(target, safe, captured, configuration, nowMillis, eventId)) }
    }

    /** 由后台维护任务调用，当前会话仍可从新代次继续记录。 */
    internal fun clear(directory: File) = synchronized(SessionLogFile.storageLock) {
        generation++
        SessionLogFile.REPORT_NAMES.forEach { name ->
            val file = File(directory, name)
            if (file.exists()) {
                if (name == "diplay.log") file.writeText("") else check(file.delete())
            }
        }
    }

    internal fun awaitIdle(timeoutMillis: Long): Boolean = writer.awaitIdle(timeoutMillis)
}
