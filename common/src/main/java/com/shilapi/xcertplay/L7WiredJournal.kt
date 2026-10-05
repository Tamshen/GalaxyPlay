package com.shilapi.xcertplay

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 两次有线尝试独立于自动连接保护留存；只接收阶段码和异常类型，不保存异常消息。 */
internal class L7WiredJournal(
    private val prefs: SharedPreferences,
    private val wall: () -> Long,
    private val mono: () -> Long,
) {
    private val attempts = runCatching {
        val raw = prefs.getString("attempts", "[]").orEmpty()
        if (raw.length > 128 * 1024) JSONArray() else JSONArray(raw)
    }.getOrElse { JSONArray() }.let { data ->
        (0 until data.length()).mapNotNull { data.optJSONObject(it) }
            .filter { it.optJSONArray("events") != null }.takeLast(2).toMutableList()
    }

    fun begin(pid: Int, version: String): String {
        val id = UUID.randomUUID().toString().take(8)
        attempts += JSONObject().put("attempt", id).put("pid", pid).put("version", code(version))
            .put("startedAt", wall()).put("monoStart", mono()).put("outcome", "RUNNING")
            .put("phase", "HOST").put("events", JSONArray()).put("sequence", 0)
        while (attempts.size > 2) attempts.removeAt(0)
        event(id, "HOST", "BEGIN")
        return id
    }

    fun event(id: String, phase: String, result: String, error: Throwable? = null, outcome: String? = null) {
        val item = attempts.lastOrNull()?.takeIf { it.optString("attempt") == id } ?: return
        val events = item.getJSONArray("events")
        val stage = code(phase)
        val state = code(result)
        val sequence = item.optInt("sequence") + 1
        val entry = JSONObject().put("seq", sequence).put("at", wall())
            .put("elapsedMs", (mono() - item.optLong("monoStart")).coerceAtLeast(0))
            .put("phase", stage).put("result", state)
        if (error != null) {
            val causes = generateSequence(error) { it.cause }.take(4).toList()
            entry.put("exception", causes.joinToString(">") { code(it.javaClass.name) }.take(320))
            entry.put("frames", causes.flatMap { it.stackTrace.toList() }
                .filter { it.className.startsWith("com.shilapi.") }.take(8)
                .joinToString("|") { "${code(it.className)}.${code(it.methodName)}:${it.lineNumber}" }.take(1200))
        }
        // 保留首个启动锚点与最近阶段，明确标记中间淘汰量。
        while (events.length() >= 24) { events.remove(1); item.put("omittedEvents", item.optInt("omittedEvents") + 1) }
        events.put(entry)
        item.put("sequence", sequence).put("phase", stage)
        if (outcome in setOf("FAILED", "JAVA_CRASH")) item.put("failurePhase", stage)
        if (outcome != null && !(outcome == "CANCELLED" && item.optString("outcome") in setOf("FAILED", "JAVA_CRASH"))) {
            item.put("outcome", code(outcome))
        }
        save(critical = result == "BEGIN" || error != null || outcome != null)
    }

    fun crash(pid: Int, error: Throwable) {
        val item = attempts.lastOrNull()?.takeIf { it.optInt("pid") == pid && it.optString("outcome") in live + "FAILED" } ?: return
        event(item.getString("attempt"), item.optString("phase"), "UNCAUGHT", error, "JAVA_CRASH")
        prefs.edit().putBoolean("notice", true).commit()
    }

    fun recover(pid: Int, exit: (Int, Long) -> Int?) {
        val item = attempts.lastOrNull() ?: return
        if (item.optInt("pid") == pid || item.optString("outcome") !in live) return
        val reason = exit(item.optInt("pid"), item.optLong("startedAt"))
        item.put("exitReason", reason ?: -1).put("outcome", when (reason) {
            4 -> "JAVA_CRASH"
            5 -> "NATIVE_CRASH"
            6 -> "ANR"
            else -> "INTERRUPTED"
        })
        // 已确认的主动退出和系统回收不弹成崩溃；未知仍提示上次未完成。
        prefs.edit().putBoolean("notice", reason == null || reason in setOf(0, 2, 4, 5, 6, 7, 13)).commit()
        save(true)
    }

    fun current(pid: Int): String? = attempts.lastOrNull()?.takeIf { it.optInt("pid") == pid }?.optString("attempt")
    fun notice() = prefs.getBoolean("notice", false)
    fun acknowledge() { prefs.edit().putBoolean("notice", false).apply() }
    fun clear() { attempts.clear(); prefs.edit().clear().commit() }

    fun report(): List<String> = attempts.flatMap { item ->
        val id = item.optString("attempt")
        val summary = "USB_STARTUP attempt=$id pid=${item.optInt("pid")} version=${item.optString("version")} " +
            "startedAt=${item.optLong("startedAt")} outcome=${item.optString("outcome")} " +
            "lastPhase=${item.optString("phase")} exitReason=${item.optInt("exitReason", -1)} " +
            "failurePhase=${item.optString("failurePhase", "none")} " +
            "omittedEvents=${item.optInt("omittedEvents")} storageConfirmed=${prefs.getBoolean("storage_confirmed", true)}"
        val events = item.getJSONArray("events")
        listOf(summary) + (0 until events.length()).map { index ->
            val event = events.getJSONObject(index)
            "USB_STARTUP attempt=$id seq=${event.getInt("seq")} at=${event.getLong("at")} " +
                "elapsedMs=${event.getLong("elapsedMs")} phase=${event.getString("phase")} " +
                "result=${event.getString("result")}" +
                if (event.has("exception")) " exception=${event.getString("exception")} frames=${event.optString("frames")}" else ""
        }
    }

    private fun save(critical: Boolean) {
        val edit = prefs.edit().putString("attempts", JSONArray(attempts).toString())
        if (critical) {
            val stored = edit.commit()
            prefs.edit().putBoolean("storage_confirmed", stored).apply()
        } else edit.apply()
    }

    companion object {
        private val live = setOf("RUNNING", "CONNECTED")
        private fun code(value: String) = value.take(160).replace(Regex("[^A-Za-z0-9_.$:-]"), "?")
    }
}
