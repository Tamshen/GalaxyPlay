package com.shilapi.xcertplay

import android.content.Context
import java.io.File

/** 轮转锁内只复制当时存在的完整文件；后续整理不占写盘锁，不跟随上传期间新增日志。 */
internal object RemoteLogArchiveInput {
    fun freeze(context: Context, directory: File, checkpoint: () -> Unit): List<File> {
        val logs = File(context.filesDir, "logs")
        return synchronized(L7ProbeLog) {
            synchronized(SessionLogFile.storageLock) {
                (SessionLogFile.REPORT_NAMES + L7ProbeLog.files).mapNotNull { name ->
                    checkpoint()
                    val source = File(logs, name)
                    if (!source.isFile) return@mapNotNull null
                    File(directory, name).also { target ->
                        source.inputStream().use { input -> target.outputStream().use { output ->
                            val buffer = ByteArray(32 * 1024)
                            while (true) {
                                checkpoint()
                                val size = input.read(buffer)
                                if (size < 0) break
                                output.write(buffer, 0, size)
                            }
                        } }
                    }
                }
            }
        }
    }

    /** 正常日志按行完整读取；异常巨行仅保留长度说明，不将裁剪后的载荷当作普通诊断。 */
    fun lines(file: File, checkpoint: () -> Unit): Sequence<String> = sequence {
        file.bufferedReader().use { reader ->
            val text = StringBuilder()
            var length = 0L
            val buffer = CharArray(8192)
            while (true) {
                checkpoint()
                val count = reader.read(buffer)
                if (count < 0) break
                for (i in 0 until count) {
                    val char = buffer[i]
                    if (char == '\n') {
                        yield(if (length > MAX_LINE) "oversized_log_line omittedChars=$length [truncated]" else text.toString().removeSuffix("\r"))
                        text.setLength(0); length = 0
                    } else {
                        length++
                        if (length <= MAX_LINE) text.append(char)
                    }
                }
            }
            if (length > 0) yield(if (length > MAX_LINE) "oversized_log_line omittedChars=$length [truncated]" else text.toString())
        }
    }

    private const val MAX_LINE = GalaxyConfigurationEvidence.MAX_BYTES + 64
}
