package com.shilapi.xcertplay

import java.io.Closeable
import java.io.File
import org.json.JSONObject

/** 更新文件清理与下载互斥，限定本应用缓存；活动文件及有效待安装包不参与过期清理。 */
internal object GalaxyUpdateFiles {
    const val RETENTION_MILLIS = 24 * 60 * 60 * 1000L
    private val active = mutableMapOf<String, Int>()
    private var installation: Closeable? = null
    private val name = Regex("download-[a-zA-Z0-9-]+\\.(apk|partial)")

    @Synchronized fun beginInstall(file: File): Boolean {
        if (installation != null) return false
        installation = protect(file)
        return true
    }

    @Synchronized fun finishInstall() { installation?.close(); installation = null }

    @Synchronized fun protect(vararg files: File): Closeable {
        val paths = files.map { it.canonicalPath }
        paths.forEach { active[it] = (active[it] ?: 0) + 1 }
        var released = false
        return Closeable { synchronized(this) {
            if (!released) {
                released = true
                paths.forEach { path ->
                    val count = (active[path] ?: 1) - 1
                    if (count == 0) active.remove(path) else active[path] = count
                }
            }
        } }
    }

    @Synchronized fun cleanup(root: File, pending: File? = null, nowMillis: Long = System.currentTimeMillis()) {
        val kept = (pending ?: recorded(root, nowMillis))?.canonicalPath
        root.listFiles()?.forEach { file ->
            if (owned(root, file) && file.canonicalPath != kept &&
                nowMillis - file.lastModified() >= RETENTION_MILLIS) delete(root, file)
        }
    }

    @Synchronized fun delete(root: File, file: File) {
        if (owned(root, file) && file.canonicalPath !in active) file.delete()
    }

    private fun owned(root: File, file: File): Boolean =
        name.matches(file.name) && file.isFile && file.canonicalFile.parentFile == root.canonicalFile &&
            file.canonicalFile.name == file.name

    private fun recorded(root: File, nowMillis: Long): File? = runCatching {
        val record = File(root, "pending.json")
        if (record.length() !in 1..GalaxyUpdateManifest.MAX_METADATA_SIZE.toLong() ||
            nowMillis - record.lastModified() >= RETENTION_MILLIS) return null
        File(root, JSONObject(record.readText()).getString("file")).takeIf { owned(root, it) }
    }.getOrNull()
}
