package com.shilapi.xcertplay

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import java.io.Closeable
import java.io.File
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** 仅保留当前会话最近四张采样封面；共享 URI 不含手机标识，断开撤销授权并删除。 */
internal class L7MediaArtwork(context: Context,
    private val workerExecutor: Executor = worker,
    private val mainExecutor: Executor = Executor { Handler(Looper.getMainLooper()).post(it) },
    private val publish: (Uri?) -> Unit) : Closeable {
    private val app = context.applicationContext
    private val directory = File(app.cacheDir, "l7-media-artwork/${UUID.randomUUID()}")
    private val cache = LinkedHashMap<Int, File>()
    private var selected: Int? = null
    private var closed = false
    private val queue = NowPlayingArtworkQueue(
        workerExecutor, mainExecutor, ::save,
        { owner, id, file -> accept(owner, id, file) }, ::remove,
    )
    private val owner = queue.newSession()

    fun select(id: Int?) {
        val selectedUri = synchronized(this) {
            if (closed) return
            selected = id
            id?.let(cache::get)?.let(::uri)
        }
        publish(selectedUri)
    }

    @Synchronized fun submit(id: Int, bytes: ByteArray) {
        if (!closed) queue.submit(owner, id, bytes)
    }

    private fun save(bytes: ByteArray): File? {
        val image = NowPlayingMetadata.decodeArtwork(bytes) ?: return null
        val file = File(directory, "${UUID.randomUUID()}.jpg")
        return try {
            directory.mkdirs()
            val written = file.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }
            if (written) file else { remove(file); null }
        } catch (error: Exception) {
            remove(file)
            L7DebugLog.record("MediaCenter: artwork exceptionType=${error.javaClass.simpleName}")
            null
        } finally { image.recycle() }
    }

    private fun accept(expected: Any, id: Int, file: File?) {
        val deliver = synchronized(this) {
            if (closed || expected !== owner) { file?.let(::remove); return }
            cache.remove(id)?.let(::remove)
            if (file != null) cache[id] = file
            while (cache.size > 4) cache.remove(cache.keys.first { it != selected })?.let(::remove)
            selected == id
        }
        // 不持封面锁进入宿主，避免与断开时的宿主→封面锁顺序相反。
        if (deliver) publish(file?.let(::uri))
    }

    private fun uri(file: File) = FileProvider.getUriForFile(app, "${app.packageName}.media-artwork", file)
    private fun remove(file: File) {
        runCatching { app.revokeUriPermission(uri(file), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        file.delete()
        directory.delete() // 仅在目录为空时成功，迟到结果也能清理。
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        queue.clear()
        cache.values.forEach(::remove)
        cache.clear()
        directory.delete()
    }

    private companion object {
        val worker = Executors.newSingleThreadExecutor { Thread(it, "l7-media-cover").apply { isDaemon = true } }
    }
}
