package com.shilapi.xcertplay

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** 取消会关闭当前连接；工作线程及临时文件由本次请求独占。 */
internal class GalaxyUpdateRequest {
    private val cancelled = AtomicBoolean(false)
    private val connection = AtomicReference<HttpURLConnection?>()
    fun cancel() { cancelled.set(true); connection.getAndSet(null)?.disconnect() }
    fun check() { if (cancelled.get() || Thread.currentThread().isInterrupted) throw GalaxyUpdateException(GalaxyUpdateFailure.CANCELLED) }
    fun attach(value: HttpURLConnection) { connection.set(value); if (cancelled.get()) { value.disconnect(); check() } }
    fun detach(value: HttpURLConnection) { connection.compareAndSet(value, null); value.disconnect() }
}

internal interface GalaxyUpdateSource {
    fun latest(packageName: String, request: GalaxyUpdateRequest): GalaxyUpdateRelease
    fun download(release: GalaxyUpdateRelease, directory: File, request: GalaxyUpdateRequest, progress: (Long) -> Unit): File
}

/** 有界下载到私有缓存，逐片和整包校验成功才交付 APK。 */
internal class GalaxyUpdateTransfer(private val endpoint: URI = GalaxyUpdateManifest.endpoint) : GalaxyUpdateSource {
    override fun latest(packageName: String, request: GalaxyUpdateRequest): GalaxyUpdateRelease = network(request) {
        val bytes = ByteArrayOutputStream()
        var base = endpoint
        get(endpoint, request) { connection ->
            base = connection.url.toURI()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                val deadline = System.nanoTime() + 30_000_000_000L
                while (true) {
                    request.check()
                    updateRequire(System.nanoTime() < deadline, GalaxyUpdateFailure.NETWORK)
                    val count = input.read(buffer)
                    if (count < 0) break
                    updateRequire(bytes.size() + count <= GalaxyUpdateManifest.MAX_METADATA_SIZE)
                    bytes.write(buffer, 0, count)
                }
            }
        }
        GalaxyUpdateManifest.parse(bytes.toString(Charsets.UTF_8.name()), base, packageName)
    }

    override fun download(release: GalaxyUpdateRelease, directory: File, request: GalaxyUpdateRequest,
                 progress: (Long) -> Unit): File = network(request) {
        updateRequire(directory.isDirectory || directory.mkdirs(), GalaxyUpdateFailure.STORAGE)
        updateRequire(directory.usableSpace >= release.size + 4 * 1024 * 1024, GalaxyUpdateFailure.STORAGE)
        directory.listFiles()?.filter { it.isFile && (it.extension == "apk" || it.extension == "partial") &&
            System.currentTimeMillis() - it.lastModified() > 24 * 60 * 60 * 1000L }?.forEach { it.delete() }
        val pending = File.createTempFile("download-", ".partial", directory)
        val target = File(directory, pending.nameWithoutExtension + ".apk")
        var delivered = false
        try {
            val whole = MessageDigest.getInstance("SHA-256")
            var completed = 0L
            val deadline = System.nanoTime() + 15 * 60_000_000_000L
            FileOutputStream(pending).use { output ->
                for (part in release.files) {
                    val digest = MessageDigest.getInstance("SHA-256")
                    var received = 0L
                    get(part.uri, request) { connection ->
                        val length = connection.contentLengthLong
                        updateRequire(length == -1L || length == part.size, GalaxyUpdateFailure.INTEGRITY)
                        connection.inputStream.use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                request.check()
                                updateRequire(System.nanoTime() < deadline, GalaxyUpdateFailure.NETWORK)
                                val count = input.read(buffer)
                                if (count < 0) break
                                received += count
                                updateRequire(received <= part.size && completed + count <= release.size, GalaxyUpdateFailure.INTEGRITY)
                                output.write(buffer, 0, count)
                                digest.update(buffer, 0, count); whole.update(buffer, 0, count)
                                completed += count; progress(completed)
                            }
                        }
                    }
                    updateRequire(received == part.size && hex(digest.digest()) == part.sha256, GalaxyUpdateFailure.INTEGRITY)
                }
                request.check()
                updateRequire(completed == release.size && hex(whole.digest()) == release.sha256, GalaxyUpdateFailure.INTEGRITY)
                output.fd.sync()
            }
            request.check()
            updateRequire(pending.renameTo(target), GalaxyUpdateFailure.STORAGE)
            delivered = true
            target
        } finally {
            pending.delete()
            if (!delivered) target.delete()
        }
    }

    private fun <T> get(uri: URI, request: GalaxyUpdateRequest, read: (HttpURLConnection) -> T): T {
        var next = uri
        repeat(4) { attempt ->
            request.check()
            updateRequire(GalaxyUpdateManifest.allowed(next, endpoint), GalaxyUpdateFailure.NETWORK)
            val connection = next.toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000; connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false; connection.useCaches = false
            connection.setRequestProperty("Accept-Encoding", "identity")
            request.attach(connection)
            try {
                when (connection.responseCode) {
                    200 -> return read(connection)
                    301, 302, 303, 307, 308 -> {
                        updateRequire(attempt < 3, GalaxyUpdateFailure.NETWORK)
                        next = next.resolve(connection.getHeaderField("Location") ?: throw GalaxyUpdateException(GalaxyUpdateFailure.NETWORK))
                        updateRequire(GalaxyUpdateManifest.allowed(next, connection.url.toURI()), GalaxyUpdateFailure.NETWORK)
                    }
                    else -> throw GalaxyUpdateException(GalaxyUpdateFailure.NETWORK)
                }
            } finally { request.detach(connection) }
        }
        throw GalaxyUpdateException(GalaxyUpdateFailure.NETWORK)
    }

    private fun <T> network(request: GalaxyUpdateRequest, work: () -> T): T = try { work() }
    catch (error: GalaxyUpdateException) { throw error }
    catch (_: IOException) { request.check(); throw GalaxyUpdateException(GalaxyUpdateFailure.NETWORK) }
    catch (_: Exception) { request.check(); throw GalaxyUpdateException(GalaxyUpdateFailure.NETWORK) }

    companion object {
        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
