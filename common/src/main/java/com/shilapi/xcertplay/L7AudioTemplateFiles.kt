package com.shilapi.xcertplay

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.AudioRoutingTemplate
import java.io.Closeable
import java.util.concurrent.Executors

/** 文件选择器只读导入；验证后由用户确认替换，导出只写用户选择的目标文件。 */
internal class L7AudioTemplateFiles(private val activity: ComponentActivity,
                                   private val changed: () -> Unit) : Closeable {
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "l7-audio-template-files").apply { isDaemon = true }
    }
    @Volatile private var closed = false
    private var exporting: String? = null
    private val importer = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !closed) worker.execute {
            val result = runCatching {
                activity.contentResolver.openInputStream(uri)?.use { L7AudioTemplates.parse(it) }
                    ?: error("文件不可读")
            }
            activity.runOnUiThread {
                if (!closed && !activity.isFinishing) {
                    result.onSuccess { confirm(it) }.onFailure { message(R.string.l7_template_invalid) }
                }
            }
        }
    }
    private val exporter = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val json = exporting
        exporting = null
        if (uri != null && json != null && !closed) worker.execute {
            val result = runCatching {
                activity.contentResolver.openOutputStream(uri, "wt")?.use {
                    it.write(json.toByteArray(Charsets.UTF_8))
                } ?: error("文件不可写")
            }
            activity.runOnUiThread {
                if (!closed && !activity.isFinishing)
                    message(if (result.isSuccess) R.string.l7_template_exported else R.string.l7_template_save_failed)
            }
        }
    }

    fun importFile() = importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))

    fun exportFile() {
        exporting = L7AudioTemplates.load(activity).toJson()
        exporter.launch("l7-audio-template.json")
    }

    fun restore(state: Bundle?) { exporting = state?.getString("audio_template_export") }
    fun save(state: Bundle) { exporting?.let { state.putString("audio_template_export", it) } }

    private fun confirm(template: AudioRoutingTemplate) {
        L7Dialogs.builder(activity).setTitle(R.string.l7_template_import)
            .setMessage(activity.getString(R.string.l7_template_import_confirm, template.name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.l7_save_next_connection) { _, _ ->
                if (runCatching { L7AudioTemplates.saveCustom(activity, template) }.isSuccess) changed()
                else message(R.string.l7_template_save_failed)
            }.show()
    }

    private fun message(id: Int) = Toast.makeText(activity, id, Toast.LENGTH_LONG).show()

    override fun close() { closed = true; worker.shutdownNow() }
}
