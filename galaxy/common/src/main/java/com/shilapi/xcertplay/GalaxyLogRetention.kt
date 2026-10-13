package com.shilapi.xcertplay

import java.io.File
import java.io.RandomAccessFile

/** 总日志预算独立于上传批次；平常只更新当前文件大小，轮转或超额时才重新核对目录。 */
internal class GalaxyLogRetention(private val directory: File) {
    private val sizes = mutableMapOf<String, Long>()
    private var initialized = false
    private var total = 0L

    fun changed(file: File, rescan: Boolean) = synchronized(SessionLogFile.storageLock) {
        if (file.canonicalFile.parentFile != directory.canonicalFile) return@synchronized
        if (!initialized || rescan) scan()
        else {
            val current = file.length()
            total += current - (sizes.put(file.name, current) ?: 0L)
        }
        if (total > MAX_BYTES) prune()
    }

    fun prune() = synchronized(SessionLogFile.storageLock) {
        scan()
        val files = files().sortedWith(compareBy<File>({ if (it.name in ACTIVE) 1 else 0 },
            { it.lastModified() }, { SessionLogFile.REPORT_NAMES.indexOf(it.name) }))
        for (file in files) {
            if (total <= MAX_BYTES) break
            val before = file.length()
            if (file.name in ACTIVE) trim(file) else file.delete()
            total -= before - file.length()
            sizes[file.name] = file.length()
        }
    }

    private fun scan() {
        sizes.clear()
        files().forEach { sizes[it.name] = it.length() }
        total = sizes.values.sum(); initialized = true
    }

    private fun files() = directory.listFiles().orEmpty().filter {
        it.isFile && it.extension == "log" && it.canonicalFile.parentFile == directory.canonicalFile &&
            it.canonicalFile.name == it.name
    }

    /** 旧版本异常大活动文件保留完整尾部行；正常活动日志已有单文件轮转限制。 */
    private fun trim(file: File) {
        if (file.length() <= SessionLogFile.MAX_BYTES) return
        RandomAccessFile(file, "rw").use { data ->
            data.seek(data.length() - SessionLogFile.MAX_BYTES)
            val bytes = ByteArray(SessionLogFile.MAX_BYTES.toInt())
            data.readFully(bytes)
            val first = bytes.indexOf('\n'.code.toByte()) + 1
            data.seek(0); data.write(bytes, first, bytes.size - first); data.setLength((bytes.size - first).toLong())
        }
    }

    companion object {
        const val MAX_BYTES = 5_000_000L
        private val ACTIVE = setOf("diplay.log", "debug.log", "steering.log", "voice-input.log", "probe-latest.log")
    }
}
