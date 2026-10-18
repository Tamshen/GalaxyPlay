package com.shilapi.xcertplay

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.Executors

/** 后台解析粘贴文本并导出文件；页面关闭后注销回调并丢弃迟到结果。 */
internal class GalaxyConfigurationFiles(private val activity: ComponentActivity,
    private val imported: (Result<GalaxyConfigurationShare>) -> Unit,
    private val saved: (Boolean) -> Unit) : Closeable {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "galaxy-config-files").apply { isDaemon = true } }
    private val key = "galaxy-config-${UUID.randomUUID()}"
    @Volatile private var closed = false
    var busy = false
        private set
    private var text: String? = null
    private var image: Bitmap? = null
    data class Prepared(val value: GalaxyConfigurationShare, val code: String, val qr: Bitmap?)
    private val jsonWriter = activity.activityResultRegistry.register("$key-json", ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val value = text; text = null
        if (uri != null && value != null && !closed) task({
            activity.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(value.toByteArray(Charsets.UTF_8)) }
        }) { saved(it.isSuccess) } else busy = false
    }
    private val imageWriter = activity.activityResultRegistry.register("$key-png", ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val value = image; image = null
        if (uri != null && value != null && !closed) task({
            activity.contentResolver.openOutputStream(uri, "wt")!!.use { check(value.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }) { saved(it.isSuccess) } else busy = false
    }
    fun parse(text: String) {
        if (busy || closed) return
        busy = true
        task({ GalaxyConfigurationShare.parse(text) }, imported)
    }

    fun prepare(profile: GalaxyProfile, name: String, done: (Result<Prepared>) -> Unit) {
        if (busy || closed) return
        busy = true
        task({
            val value = GalaxyConfigurationShare.from(profile, name)
            val code = value.code()
            Prepared(value, code, runCatching { GalaxyConfigurationQr.encode(code) }.getOrNull())
        }, done)
    }

    fun exportFile(value: GalaxyConfigurationShare) = launch {
        text = value.json()
        jsonWriter.launch("${fileName(value.name)}.galaxyplay.json")
    }

    fun exportImage(name: String, bitmap: Bitmap) = launch {
        image = bitmap
        imageWriter.launch("${fileName(name)}.png")
    }

    private fun launch(action: () -> Unit) {
        if (busy || closed) return
        busy = true
        runCatching(action).onFailure { busy = false; text = null; image = null; saved(false) }
    }

    private fun <T> task(action: () -> T, done: (Result<T>) -> Unit) {
        if (closed) return
        worker.execute {
            val result = runCatching(action)
            activity.runOnUiThread {
                busy = false
                if (!closed && !activity.isFinishing && !activity.isDestroyed) done(result)
            }
        }
    }

    override fun close() {
        closed = true
        worker.shutdownNow()
        jsonWriter.unregister(); imageWriter.unregister()
        text = null; image = null
    }

    companion object {
        private fun fileName(name: String) = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(40)
    }
}
