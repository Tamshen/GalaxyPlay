package com.shilapi.xcertplay

import java.io.Closeable
import java.io.File

/** 每行先脱敏再落盘，按 UTF-8 字节预算轮转并保留最近八份日志。 */
internal class SessionLogFile(val file: File) : Closeable {
    private val lock = storageLock
    private var closed = false
    fun reset(header: String) = synchronized(lock) {
        if (!closed) {
            file.parentFile?.mkdirs()
            rotate()
            file.writeText("")
            append(header)
        }
    }
    fun append(line: String) = synchronized(lock) {
        if (closed) return@synchronized
        val safe = DiagnosticRedactor.redact(line) ?: return@synchronized
        val bytes = (safe + "\n").toByteArray(Charsets.UTF_8)
        runCatching {
            if (file.length() + bytes.size > MAX_BYTES) {
                rotate()
                file.writeText("")
            }
            file.appendBytes(bytes)
        }
        Unit
    }
    private fun rotate() {
        if (!file.exists() || file.length() == 0L) return
        for (index in ARCHIVE_NAMES.lastIndex downTo 1) {
            val source = File(file.parentFile, ARCHIVE_NAMES[index - 1])
            val destination = File(file.parentFile, ARCHIVE_NAMES[index])
            if (source.exists()) source.copyTo(destination, overwrite = true)
        }
        file.copyTo(File(file.parentFile, ARCHIVE_NAMES.first()), overwrite = true)
    }
    override fun close() = synchronized(lock) { closed = true }
    companion object {
        internal val storageLock = Any()
        const val MAX_BYTES = 512 * 1024L
        private val ARCHIVE_NAMES = listOf("previous.log") + (2..7).map { "previous-$it.log" }
        val REPORT_NAMES = ARCHIVE_NAMES.reversed() + "diplay.log"
    }
}
