package com.shilapi.xcertplay

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 队列只保存所属会话文件与已脱敏、限长的元数据。 */
internal object AsyncDiagnosticLog {
    private data class Entry(val target: SessionLogFile, val line: String)
    private val writer = BoundedDiagnosticWriter<Entry> { it.target.append(it.line) }

    fun append(target: SessionLogFile?, message: String, nowMillis: Long = System.currentTimeMillis()) {
        if (target == null) return
        val safe = DiagnosticRedactor.redact(message) ?: return
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(nowMillis))
        runCatching { writer.enqueue(Entry(target, "$timestamp  $safe")) }
    }

    internal fun awaitIdle(timeoutMillis: Long): Boolean = writer.awaitIdle(timeoutMillis)
}
