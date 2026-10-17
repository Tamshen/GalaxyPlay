package com.shilapi.xcertplay

import java.io.Closeable
import java.io.File

/** 每行先脱敏再落盘，按 UTF-8 字节预算轮转并保留最近八份日志。 */
internal class SessionLogFile(val file: File, private val archiveNames: List<String> = ARCHIVE_NAMES) : Closeable {
    private val lock = storageLock
    private var closed = false
    @Volatile var configuration: GalaxyConfigurationEvidence? = null
    private var writtenConfiguration: String? = null
    fun reset(header: String) = synchronized(lock) {
        if (!closed) {
            file.parentFile?.mkdirs()
            rotate()
            file.writeText("")
            writtenConfiguration = null
            notifyStorage(true)
            append(header)
        }
    }
    fun append(line: String, evidence: GalaxyConfigurationEvidence? = configuration) = synchronized(lock) {
        if (closed) return@synchronized
        val safe = DiagnosticRedactor.redact(line) ?: return@synchronized
        appendRedactedBatch(listOf(safe), evidence)
    }

    /** 内部安全正文按原顺序批量追加；每条仍遵守配置头和 UTF-8 轮转边界。 */
    internal fun appendRedactedBatch(lines: List<String>, evidence: GalaxyConfigurationEvidence?) = synchronized(lock) {
        if (closed || lines.isEmpty()) return@synchronized
        runCatching {
            file.parentFile?.mkdirs()
            var size = file.length()
            var rotated = false
            var stream = file.outputStreamAppend()
            try {
                for (line in lines) {
                    val bytes = ((evidence?.let { "config_ref=${it.id} " } ?: "") + line + "\n").toByteArray(Charsets.UTF_8)
                    var header = evidence?.takeIf { writtenConfiguration != it.id || size == 0L }
                        ?.let { (it.header + "\n").toByteArray(Charsets.UTF_8) }
                    if (size + bytes.size + (header?.size ?: 0) > MAX_BYTES) {
                        stream.close(); rotate(); file.writeText("")
                        stream = file.outputStreamAppend()
                        size = 0; rotated = true; writtenConfiguration = null
                        header = evidence?.let { (it.header + "\n").toByteArray(Charsets.UTF_8) }
                    }
                    if (header != null) {
                        stream.write(header); size += header.size
                        writtenConfiguration = evidence?.id
                    }
                    stream.write(bytes); size += bytes.size
                }
            } finally { stream.close() }
            notifyStorage(rotated)
        }.onFailure { writtenConfiguration = null }
        Unit
    }

    private fun File.outputStreamAppend() = java.io.BufferedOutputStream(java.io.FileOutputStream(this, true), 8192)
    private fun rotate() {
        if (!file.exists() || file.length() == 0L) return
        for (index in archiveNames.lastIndex downTo 1) {
            val source = File(file.parentFile, archiveNames[index - 1])
            val destination = File(file.parentFile, archiveNames[index])
            if (source.exists()) source.copyTo(destination, overwrite = true)
        }
        file.copyTo(File(file.parentFile, archiveNames.first()), overwrite = true)
    }
    override fun close() = synchronized(lock) { closed = true }
    private fun notifyStorage(rescan: Boolean) { runCatching { storageChanged?.invoke(file, rescan) } }
    companion object {
        internal val storageLock = Any()
        @Volatile internal var storageChanged: ((File, Boolean) -> Unit)? = null
        const val MAX_BYTES = 512 * 1024L
        private val ARCHIVE_NAMES = listOf("previous.log") + (2..7).map { "previous-$it.log" }
        val REPORT_NAMES = ARCHIVE_NAMES.reversed() + "diplay.log" + listOf("steering-previous-2.log", "steering-previous.log", "steering.log",
            "voice-input-previous-2.log", "voice-input-previous.log", "voice-input.log",
            "debug-previous-2.log", "debug-previous.log", "debug.log")
    }
}
