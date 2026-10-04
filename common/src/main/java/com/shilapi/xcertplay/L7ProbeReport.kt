package com.shilapi.xcertplay

import org.json.JSONArray
import org.json.JSONObject

/** 结构化证据与运行状态分开；权限获授只能证明授权，不能证明业务接口可用。 */
internal enum class L7ProbeOutcome { VERIFIED, OBSERVED, NOT_OBSERVED, DENIED, FAILED, UNKNOWN, SKIPPED, NOT_APPLICABLE }
internal enum class L7ProbePhase { RUNNING, COMPLETED, CANCELLED, TIMED_OUT, INTERRUPTED }

internal data class L7ProbeItem(
    val id: String,
    val name: String,
    val domain: String,
    val result: L7ProbeOutcome,
    val reason: String,
    val facts: Map<String, String?> = emptyMap(),
    val time: Long = System.currentTimeMillis(),
    val elapsed: Long = android.os.SystemClock.elapsedRealtime(),
) {
    fun json() = JSONObject().put("capabilityId", id).put("name", name).put("domain", domain)
        .put("mode", "S").put("result", result.name).put("reason", reason).put("time", time)
        .put("elapsedRealtime", elapsed).put("evidenceLevel", if (reason == "NOT_RUN") JSONObject.NULL else "QUERY")
        .put("executionState", if (reason == "NOT_RUN") "NOT_RUN" else "COMPLETED")
        .put("facts", JSONObject().apply { facts.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) } })

    companion object {
        fun read(json: JSONObject) = L7ProbeItem(json.getString("capabilityId"), json.getString("name"),
            json.getString("domain"), L7ProbeOutcome.valueOf(json.getString("result")), json.getString("reason"),
            json.getJSONObject("facts").let { facts -> facts.keys().asSequence().associateWith {
                if (facts.isNull(it)) null else facts.getString(it)
            } }, json.getLong("time"), json.getLong("elapsedRealtime"))
    }
}

internal data class L7ProbeReport(
    val id: String,
    val environment: String,
    val version: String,
    val started: Long,
    val phase: L7ProbePhase,
    val items: List<L7ProbeItem> = emptyList(),
    val expected: Int = 0,
    val finished: Long? = null,
    val truncated: Boolean = false,
) {
    fun json() = JSONObject().put("schemaVersion", 1).put("probeVersion", 1).put("runId", id)
        .put("caseId", "BASIC").put("executorContext", "L7_APP").put("environmentGeneration", environment)
        .put("appVersion", version).put("startedAt", started).put("finishedAt", finished ?: JSONObject.NULL)
        .put("executionState", phase.name).put("expectedItems", expected).put("truncated", truncated)
        .put("items", JSONArray(items.map { it.json() }))

    fun counts() = L7ProbeOutcome.entries.associateWith { result -> items.count { it.result == result } }

    companion object {
        fun read(json: JSONObject): L7ProbeReport {
            require(json.getInt("schemaVersion") == 1 && json.getString("executorContext") == "L7_APP")
            return L7ProbeReport(json.getString("runId"), json.getString("environmentGeneration"),
                json.getString("appVersion"), json.getLong("startedAt"),
                L7ProbePhase.valueOf(json.getString("executionState")),
                json.getJSONArray("items").let { array -> (0 until array.length()).map { L7ProbeItem.read(array.getJSONObject(it)) } },
                json.getInt("expectedItems"), if (json.isNull("finishedAt")) null else json.getLong("finishedAt"),
                json.optBoolean("truncated"))
        }
    }
}
