package com.shilapi.xcertplay

import android.content.Context
import android.net.Uri
import java.io.Closeable
import java.io.File
import java.util.UUID

/** DIM 参考实现读取文件 URI；仅复制自身封面，所有操作由同一 DIM worker 串行调用。 */
internal class GalaxyDimArtwork(private val context: Context) : Closeable {
    private val directory = context.externalMediaDirs.firstOrNull { it != null }
        ?.let { File(it, "dim-music/${UUID.randomUUID()}") }
    private val files = LinkedHashMap<Uri, File>()
    private val lease = directory?.let(GalaxyTemporaryFiles::protect)
    private var pruned = false
    fun prepare(uri: Uri?): Uri? {
        if (uri == null || uri.scheme != "content" || uri.authority != "${context.packageName}.media-artwork") return null
        files[uri]?.takeIf { it.isFile }?.let { return Uri.fromFile(it) }
        val folder = directory ?: return null
        val file = File(folder, "${UUID.randomUUID()}.jpg")
        return try {
            if (!pruned) { GalaxyTemporaryFiles.cleanupDim(context); pruned = true }
            folder.mkdirs()
            File(folder.parentFile, ".nomedia").createNewFile()
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        check(total <= 1024 * 1024)
                        output.write(buffer, 0, count)
                    }
                    check(total > 0)
                }
            } ?: return null
            files[uri] = file
            while (files.size > 4) files.remove(files.keys.first())?.delete()
            Uri.fromFile(file)
        } catch (_: Exception) { file.delete(); null }
    }
    override fun close() {
        files.values.forEach { it.delete() }
        files.clear()
        directory?.delete()
        lease?.close()
    }
}
