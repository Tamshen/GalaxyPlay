package com.shilapi.xcertplay

import android.content.Context
import org.json.JSONObject

/** 应用偏好使用原存储，车型模板与会话草稿均无权覆盖；诊断单独捕获。 */
internal object GalaxyApplicationPreferences {
    fun contains(space: String, key: String): Boolean = GalaxyConfigurationFields.allowed(space, key) &&
        !GalaxyConfigurationFields.vehicleAllowed(space, key)
    fun vehicle(configuration: GalaxyConfiguration) = configuration.copy(preferences =
        configuration.preferences.mapValues { (space, values) -> values.filterKeys {
            GalaxyConfigurationFields.vehicleAllowed(space, it)
        } })

    fun capture(context: Context): JSONObject {
        val actual = if (context is GalaxyConfigurationContext) context.baseContext else context
        val groups = GalaxyConfigurationFields.defaults(actual, vehicleOnly = false).mapValues { (space, values) ->
            values.filterKeys { contains(space, it) }.toMutableMap()
        }
        groups.forEach { (space, values) -> actual.getSharedPreferences(space, 0).all.forEach { (key, value) ->
            if (contains(space, key)) values[key] = value
        } }
        groups.getValue("diplay")["app_language"] = AppLocale.preference(actual)
        groups.getValue("xcertplay_airplay")["manual_hotspot_security"] = AirPlayPersistence.loadManualHotspotSecurity(actual).name
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
