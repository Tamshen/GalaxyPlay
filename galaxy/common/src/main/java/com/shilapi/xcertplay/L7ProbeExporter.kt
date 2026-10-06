package com.shilapi.xcertplay

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.shilapi.xcertplay.host.R
import java.io.File
import java.lang.ref.WeakReference

/** 导出只写所选报告；写完后才报告保存位置，不触发远程上传。 */
internal class L7ProbeExporter(activity: ComponentActivity) {
    private val owner = WeakReference(activity)
    // 组件随 Activity 字段注册结果回调，此时尚未 attach，应用上下文在实际导出时获取。
    private val app by lazy { requireNotNull(owner.get()).applicationContext }
    var pendingId: String? = null
    private val destination = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val id = pendingId
        pendingId = null
        if (uri != null && id != null) write(id, uri)
    }

    fun export(report: L7ProbeReport, choose: Boolean) {
        if (busy) return
        if (choose) {
            pendingId = report.id
            runCatching { destination.launch(fileName(report.id)) }.onFailure {
                pendingId = null
                owner.get()?.let { L7Notice.show(it, it.getString(R.string.l7_probe_export_failed)) }
            }
        } else write(report.id, null)
    }

    private fun write(id: String, uri: Uri?) {
        synchronized(Companion) { if (busy) return; busy = true }
        val appContext = app
        val current = L7ProbeRunner.current?.takeIf { it.id == id }
            ?: L7ProbeRunner.history.find { it.id == id }
        Thread({
            val result = runCatching {
                val report = current ?: L7ProbeStore(File(appContext.filesDir, "probe-reports")).load().find { it.id == id }
                    ?: error("Report unavailable")
                val body = report.json().toString(2)
                if (uri != null) { DiagnosticExportStore.write(appContext.contentResolver, uri, body); uri.toString() }
                else {
                    DiagnosticExportStore.saveToDownloads(appContext.contentResolver, fileName(id), body,
                        "application/json", "GalaxyPlay")
                    "Downloads/GalaxyPlay/${fileName(id)}"
                }
            }
            busy = false
            owner.get()?.let { activity -> activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed) L7Notice.show(activity,
                    result.fold({ activity.getString(R.string.l7_probe_saved, it) },
                        { activity.getString(R.string.l7_probe_export_failed) }))
            } }
        }, "l7-probe-export").apply { isDaemon = true; start() }
    }

    private fun fileName(id: String) = "GalaxyPlay-check-$id.json"
    companion object { @Volatile var busy = false; private set }
}
