package com.shilapi.xcertplay

import android.content.Context
import org.json.JSONObject
import com.shilapi.xcertplay.host.R

/** 应用偏好使用原存储，车型模板与会话草稿均无权覆盖；诊断单独捕获。 */
internal object GalaxyApplicationPreferences {
    fun contains(space: String, key: String): Boolean = when (space) {
        "l7_ui", "l7_floating_navigation", "l7_remote_log" -> GalaxyConfigurationFields.allowed(space, key)
        "diplay" -> key == "app_language"
        "xcertplay_airplay" -> key in setOf("debug_logs_enabled", "carplay_night_mode", "ambient_delay_seconds", "ambient_lux_threshold")
        else -> false
    }
    fun vehicle(configuration: GalaxyConfiguration) = configuration.copy(preferences =
        configuration.preferences.mapValues { (space, values) -> values.filterKeys { !contains(space, it) } })

    fun capture(context: Context): JSONObject {
        val actual = if (context is GalaxyConfigurationContext) context.baseContext else context
        val groups = GalaxyConfigurationFields.names.associateWith { mutableMapOf<String, Any?>() }
        GalaxyConfigurationFields.fields.filter { contains(it.space, it.key) }.forEach {
            groups.getValue(it.space)[it.key] = it.default
        }
        groups.getValue("l7_floating_navigation").putAll(mapOf("x" to 0f, "y" to .45f))
        groups.getValue("l7_remote_log").putAll(mapOf(
            "endpoint" to actual.getString(R.string.l7_log_default_url),
            "authorization" to actual.getString(R.string.l7_log_default_authorization)))
        groups.forEach { (space, values) -> actual.getSharedPreferences(space, 0).all.forEach { (key, value) ->
            if (contains(space, key)) values[key] = value
        } }
        groups.getValue("diplay")["app_language"] = AppLocale.preference(actual)
        return GalaxyConfiguration(groups.filterValues { it.isNotEmpty() }, emptyMap()).json().getJSONObject("preferences")
    }

    /** 与车型配置共用有界类型校验；未知字段不能作为附件注入凭据。 */
    fun read(input: JSONObject): JSONObject {
        val configuration = GalaxyConfiguration.parse(JSONObject().put("preferences", input)
            .put("audio_templates", JSONObject()))
        configuration.preferences.forEach { (space, values) -> require(values.keys.all { contains(space, it) }) }
        return configuration.json().getJSONObject("preferences")
    }
}
