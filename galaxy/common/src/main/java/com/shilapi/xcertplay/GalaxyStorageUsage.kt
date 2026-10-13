package com.shilapi.xcertplay

import android.content.Context
import android.os.Debug
import java.io.File

/** 文件逻辑大小与进程 PSS 分开统计，扫描不读取正文，不跟随符号链接到其他目录。 */
internal object GalaxyStorageUsage {
    data class Snapshot(val total: Long, val logs: Long, val cache: Long, val other: Long, val memory: Long)

    fun read(context: Context): Snapshot {
        val total = bytes(context.dataDir) + bytes(context.getExternalFilesDir(null)) + bytes(context.externalCacheDir)
        val logs = bytes(File(context.filesDir, "logs"))
        val cache = bytes(context.cacheDir) + bytes(context.codeCacheDir) + bytes(context.externalCacheDir)
        val memory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss * 1024L
        return Snapshot(total, logs, cache, (total - logs - cache).coerceAtLeast(0), memory)
    }

    fun bytes(root: File?): Long {
        if (root == null || !root.exists()) return 0
        val canonical = root.canonicalFile
        return canonical.walkTopDown().onEnter { folder -> folder == canonical ||
            folder.canonicalFile.parentFile == folder.parentFile.canonicalFile && folder.canonicalFile.name == folder.name }
            .filter { it.isFile && it.canonicalFile.parentFile == it.parentFile.canonicalFile && it.canonicalFile.name == it.name }
            .sumOf { it.length() }
    }
}
