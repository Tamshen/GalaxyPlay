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
    private val report: (String) -> Unit = L7DebugLog::record,
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
    private var owner = queue.newSession()
    private val generation = generations.incrementAndGet()
    private var lastSelection: Pair<Int?, Uri?>? = null

    fun select(id: Int?) {
        val selectedUri = synchronized(this) {
            if (closed) return
            selected = id
            id?.let(cache::get)?.let(::uri)
        }
        val selection = id to selectedUri
        if (lastSelection != selection) {
            lastSelection = selection
            event("select transferId=${id ?: "none"} cached=${selectedUri != null}")
        }
        publish(selectedUri)
    }

    @Synchronized fun selectedUri(id: Int?): Uri? =
        if (closed || selected != id) null else id?.let(cache::get)?.let(::uri)

    @Synchronized fun submit(id: Int, bytes: ByteArray) {
        if (closed) return
        val accepted = id in 0..255 && bytes.size <= com.shilapi.xcertplay.transport.Iap2FileTransferReceiver.DEFAULT_MAXIMUM_ARTWORK_BYTES
        event("receive transferId=$id bytes=${bytes.size} accepted=$accepted")
        if (accepted) queue.submit(owner, id, bytes)
    }

    /** 手机清空当前媒体后，传输 ID 可复用，旧缓存和迟到解码结果不得进入下一曲。 */
    @Synchronized fun reset() {
        if (closed) return
        owner = queue.newSession()
        cache.values.forEach(::remove)
        cache.clear()
        selected = null
        lastSelection = null
        event("reset")
    }

    private fun save(bytes: ByteArray): File? {
        val image = NowPlayingMetadata.decodeArtwork(bytes) ?: run {
            event("decode result=EMPTY_OR_INVALID bytes=${bytes.size}")
            return null
        }
        val file = File(directory, "${UUID.randomUUID()}.jpg")
        return try {
            directory.mkdirs()
            val written = file.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }
            event("cache result=${if (written) "SAVED" else "FAILED"} coverKey=${file.name.take(8)} width=${image.width} height=${image.height} bytes=${file.length()}")
            if (written) file else { remove(file); null }
        } catch (error: Exception) {
            remove(file)
            event("cache exceptionType=${error.javaClass.simpleName}")
            null
        } finally { image.recycle() }
    }

    private fun accept(expected: Any, id: Int, file: File?) {
        val deliver = synchronized(this) {
            if (closed || expected !== owner) { file?.let(::remove); return }
            cache.remove(id)?.let(::remove)
            if (file != null) cache[id] = file
            while (cache.size > 4) cache.remove(cache.keys.first { it != selected })?.let(::remove)
            event("accept transferId=$id coverKey=${file?.name?.take(8) ?: "none"} selected=${selected == id} available=${file != null}")
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

    private fun event(body: String) = report("MediaCenter: artwork generation=$generation monoMs=${android.os.SystemClock.elapsedRealtime()} $body")

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        queue.clear()
        cache.values.forEach(::remove)
        cache.clear()
        directory.delete()
        event("close")
    }

    private companion object {
        val generations = java.util.concurrent.atomic.AtomicLong()
        val worker = Executors.newSingleThreadExecutor { Thread(it, "l7-media-cover").apply { isDaemon = true } }
    }
}
