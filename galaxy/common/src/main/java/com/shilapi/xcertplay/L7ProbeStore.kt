package com.shilapi.xcertplay

import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/** 只接受采集器的白名单字段；原始唯一标识、地址、路径和认证内容不进入该报告。 */
internal class L7ProbeStore(private val directory: File) {
    fun save(report: L7ProbeReport): L7ProbeReport {
        directory.mkdirs()
        var retained = report
        var bytes = retained.json().toString().toByteArray(Charsets.UTF_8)
        while (bytes.size > MAX_BYTES && retained.items.isNotEmpty()) {
            retained = retained.copy(items = retained.items.dropLast(1), truncated = true)
            bytes = retained.json().toString().toByteArray(Charsets.UTF_8)
        }
        require(bytes.size <= MAX_BYTES)
        val file = AtomicFile(path(report.id))
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
        val reports = files()
        var total = 0L
        reports.forEachIndexed { index, saved ->
            total += saved.length()
            if (index >= MAX_REPORTS || total > MAX_TOTAL) check(saved.delete())
        }
        return retained
    }

    fun load(): List<L7ProbeReport> = files().take(MAX_REPORTS).mapNotNull { file ->
        runCatching {
            require(file.length() <= MAX_BYTES)
            val report = L7ProbeReport.read(JSONObject(AtomicFile(file).openRead().bufferedReader().use { it.readText() }))
            require(path(report.id).name == file.name)
            if (report.phase == L7ProbePhase.RUNNING) save(report.copy(phase = L7ProbePhase.INTERRUPTED,
                finished = System.currentTimeMillis())) else report
        }.getOrNull()
    }

    fun clear() {
        directory.listFiles().orEmpty().filter { it.name.matches(Regex("[a-f0-9-]{36}\\.json(?:\\.bak|\\.new)?")) }
            .forEach { check(it.delete()) }
    }

    fun delete(id: String) { AtomicFile(path(id)).delete(); check(!path(id).exists()) }
    private fun path(id: String): File {
        require(id.matches(Regex("[a-f0-9-]{36}")))
        return File(directory, "$id.json")
    }
    private fun files() = directory.listFiles().orEmpty().filter { it.extension == "json" }
        .sortedByDescending { it.lastModified() }

    companion object {
        const val MAX_BYTES = 2 * 1024 * 1024
        const val MAX_REPORTS = 10
        const val MAX_TOTAL = 20 * 1024 * 1024
    }
}
