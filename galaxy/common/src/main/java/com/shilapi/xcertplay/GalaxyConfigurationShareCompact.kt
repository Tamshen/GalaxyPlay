package com.shilapi.xcertplay

import org.json.JSONArray
import org.json.JSONObject

/** 二维码只携带固定基线编号和差异；完整文件仍沿用格式 1，默认值更新不改变旧码含义。 */
internal object GalaxyConfigurationShareCompact {
    fun encode(value: GalaxyConfigurationShare): String {
        val current = value.configuration.json()
        val candidates = GalaxyConfigurationShareBase.ids.map { id ->
            id to differences(GalaxyConfigurationShareBase.configuration(id).json(), current)
        }
        val (base, changes) = candidates.minBy { it.second.toString().toByteArray(Charsets.UTF_8).size }
        return JSONObject(value.json()).apply {
            remove("configuration")
            put("version", 2); put("baseline", 1); put("base", base); put("changes", changes)
        }.toString()
    }

    fun decode(data: JSONObject): GalaxyConfiguration {
        require(data.getInt("baseline") == 1)
        val result = GalaxyConfigurationShareBase.configuration(data.getString("base")).json()
        val changes = data.getJSONArray("changes")
        require(changes.length() <= 1024)
        val seen = mutableSetOf<List<String>>()
        for (index in 0 until changes.length()) {
            val change = changes.getJSONArray(index)
            require(change.length() in 1..2)
            val raw = change.getJSONArray(0)
            require(raw.length() in 1..5)
            val path = (0 until raw.length()).map { raw.getString(it).also { key ->
                require(key.isNotEmpty() && key.length <= 128)
            } }
            require(path.first() in setOf("preferences", "audio_templates") && seen.add(path))
            var target = result
            path.dropLast(1).forEach { target = target.getJSONObject(it) }
            if (change.length() == 1) target.remove(path.last()) else target.put(path.last(), change.get(1))
        }
        return GalaxyConfiguration.parse(result)
    }

    private fun differences(base: JSONObject, current: JSONObject): JSONArray {
        val result = JSONArray()
        fun visit(before: JSONObject, after: JSONObject, parent: List<String>) {
            (before.keys().asSequence().toSet() + after.keys().asSequence().toSet()).sorted().forEach { key ->
                val path = parent + key
                when {
                    !after.has(key) -> result.put(JSONArray().put(JSONArray(path)))
                    !before.has(key) -> result.put(JSONArray().put(JSONArray(path)).put(after.get(key)))
                    before.get(key) is JSONObject && after.get(key) is JSONObject ->
                        visit(before.getJSONObject(key), after.getJSONObject(key), path)
                    !same(before.get(key), after.get(key)) ->
                        result.put(JSONArray().put(JSONArray(path)).put(after.get(key)))
                }
            }
        }
        visit(base, current, emptyList())
        return result
    }

    private fun same(before: Any, after: Any): Boolean = if (before is JSONArray && after is JSONArray)
        before.toString() == after.toString() else before == after
}
