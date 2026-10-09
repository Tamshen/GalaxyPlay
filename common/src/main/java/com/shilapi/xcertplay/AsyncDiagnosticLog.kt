package com.shilapi.xcertplay

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 队列只保存所属会话文件与已脱敏元数据；清空后拒绝旧代次尚未落盘的记录。 */
internal object AsyncDiagnosticLog {
    private data class Entry(val target: SessionLogFile, val line: String, val generation: Long,
        val configuration: GalaxyConfigurationEvidence?)
    @Volatile private var generation = 0L
    private val writer = BoundedDiagnosticWriter<Entry> {
        synchronized(SessionLogFile.storageLock) {
            if (it.generation == generation) it.target.append(it.line, it.configuration)
        }
    }

    fun append(target: SessionLogFile?, message: String, nowMillis: Long = System.currentTimeMillis(),
        configuration: GalaxyConfigurationEvidence? = target?.configuration) {
        if (target == null) return
        val captured = generation
        val safe = DiagnosticRedactor.redact(message) ?: return
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(nowMillis))
        runCatching { writer.enqueue(Entry(target, "$timestamp  $safe", captured, configuration)) }
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
