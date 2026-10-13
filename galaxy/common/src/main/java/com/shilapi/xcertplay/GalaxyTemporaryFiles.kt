package com.shilapi.xcertplay

import android.content.Context
import java.io.Closeable
import java.io.File

/** 只管理本应用生成的临时目录；活动上传和封面独占保护，进程遗留在启动／定期维护清理。 */
internal object GalaxyTemporaryFiles {
    private val active = mutableMapOf<String, Int>()
    const val SNAPSHOT_BUDGET = 32 * 1024 * 1024L

    @Synchronized fun protect(directory: File): Closeable {
        val path = directory.canonicalPath
        active[path] = (active[path] ?: 0) + 1
        var released = false
        return Closeable { synchronized(this) {
            if (!released) {
                released = true
                val count = (active[path] ?: 1) - 1
                if (count == 0) active.remove(path) else active[path] = count
            }
        } }
    }

    @Synchronized fun cleanup(context: Context) {
        cleanDirectories(File(context.cacheDir, "log-upload-snapshots"), Regex("upload-[a-zA-Z0-9-]+"))
        cleanDirectories(File(context.cacheDir, "l7-media-artwork"), Regex("[a-f0-9-]{36}"))
        val temporary = File(context.cacheDir, "app-updates/pending.tmp")
        if (temporary.isFile && System.currentTimeMillis() - temporary.lastModified() >= 60_000) temporary.delete()
        GalaxyUpdateCache.load(context)
    }

    private fun cleanDirectories(root: File, name: Regex) {
        root.listFiles().orEmpty().filter {
            name.matches(it.name) && it.canonicalFile.parentFile == root.canonicalFile &&
                it.canonicalFile.name == it.name && it.canonicalPath !in active
        }.forEach { it.deleteRecursively() }
    }

    fun checkSnapshot(directory: File) {
        check(GalaxyStorageUsage.bytes(directory) <= SNAPSHOT_BUDGET) { "snapshot storage budget exceeded" }
    }
}
