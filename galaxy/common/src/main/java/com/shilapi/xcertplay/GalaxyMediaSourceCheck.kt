package com.shilapi.xcertplay

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import java.io.Closeable
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** 配置、服务运行态和控件列表分别取证；核对失败不破坏已有效的音乐 token。 */
internal class GalaxyMediaSourceCheck(
    private val context: Context,
    private val api: Any,
    private val apiType: Class<*>,
    private val source: Int,
    private val active: () -> Boolean,
    private val redeclare: () -> Unit,
    private val facts: (Map<String, Long>) -> Unit,
    private val report: (String) -> Unit = L7DebugLog::record,
) : Closeable {
    @Volatile private var closed = false
    private val pending = mutableListOf<ScheduledFuture<*>>()
    private fun current() = !closed && active()

    @Synchronized fun start() {
        if (closed || pending.isNotEmpty()) return
        for (delay in listOf(0L, 1000L, 3000L)) {
            pending += worker.schedule({
                if (current()) runCatching { inspect(delay == 0L) }.onFailure {
                    if (current()) report("MediaCenter: sourceCheck exceptionType=${it.javaClass.simpleName}")
                }
            }, delay, TimeUnit.MILLISECONDS)
        }
    }

    private fun inspect(repair: Boolean) {
        if (repair) {
            val catalog = GalaxyOemSourceRegistration.ensure(context, api, apiType, source, ::current)
            if (!current()) return
            facts(mapOf("catalog" to GalaxyMediaReportEvidence.catalogResults.indexOf(catalog).toLong()))
            report("MediaCenter: sourceCatalog=$catalog source=$source")
            // 只在本轮确实修复目录后重申一次；原厂会重算配置与注册来源的交集。
            if (catalog == "ADDED" || catalog == "UPDATED") redeclare()
        }
        if (!current()) return
        val controller = apiType.getMethod("getEasMediaController").invoke(api)
        val type = Class.forName("ecarx.xsf.mediacenter.session.EasMediaController", false, apiType.classLoader)
        val item = Class.forName("ecarx.xsf.mediacenter.session.AppSourceInfo", false, apiType.classLoader)
        fun listState(method: String): Long = runCatching {
            val entries = type.getMethod(method).invoke(controller) as? List<*> ?: return@runCatching -1L
            val own = entries.filterNotNull().firstOrNull {
                item.getMethod("getPackageName").invoke(it) == context.packageName
            } ?: return@runCatching 0L
            val sources = item.getMethod("getSourceTypeList").invoke(own) as? IntArray
            val running = item.getMethod("isRunning").invoke(own) == true
            val priority = item.getMethod("getPriorityLevel").invoke(own) as? Int
            if (current()) report("MediaCenter: sourceCheck layer=$method present=true " +
                "sourceIncluded=${sources?.contains(source) == true} running=$running priority=$priority")
            if (sources?.contains(source) == true) 1L else 0L
        }.getOrDefault(-1L)
        val result = mapOf("catalogPresent" to listState("getAppSourceConfig"),
            "registeredSourcePresent" to listState("getAppSourceInfo"),
            "runtimeSourcePresent" to listState("getCustomAppSourceInfo"), "uiSourcePresent" to uiState())
        if (!current()) return
        facts(result)
        report("MediaCenter: sourceCheck " + result.entries.joinToString(" ") { "${it.key}=${it.value}" })
    }

    private fun uiState(): Long = runCatching {
        context.contentResolver.query(Uri.parse("content://com.flyme.auto.mediacontrol.provider/AppSourceList"),
            null, null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex("appSourceList")
            if (column < 0 || !cursor.moveToFirst()) return@use -1L
            uiContains(cursor.getString(column), context.packageName)
        } ?: -1L
    }.getOrDefault(-1L)

    @Synchronized override fun close() {
        closed = true
        pending.forEach { it.cancel(false) }
        pending.clear()
    }

    companion object {
        internal fun uiContains(json: String?, packageName: String): Long = runCatching {
            val list = JSONArray(json ?: return@runCatching -1L)
            for (i in 0 until list.length()) {
                val item = list.getJSONObject(i)
                if (!item.has("packageName")) return@runCatching -1L
                if (item.getString("packageName") == packageName) return@runCatching 1L
            }
            0L
        }.getOrDefault(-1L)
        private val worker = ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "galaxy-source-check").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
    }
}
