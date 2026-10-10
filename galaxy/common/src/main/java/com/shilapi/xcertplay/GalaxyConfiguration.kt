package com.shilapi.xcertplay

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** 配置只包含用户参数；身份、配对、协议和运行记录留在原存储。 */
internal data class GalaxyConfiguration(
    val preferences: Map<String, Map<String, Any?>>,
    val audio: Map<String, String>,
    val audioRecovery: Set<String> = emptySet(),
) {
    fun json(): JSONObject = JSONObject().put("preferences", JSONObject().also { groups ->
        preferences.toSortedMap().forEach { (name, values) ->
            groups.put(name, JSONObject().also { group ->
                values.toSortedMap().forEach { (key, value) -> group.put(key, encode(value)) }
            })
        }
    }).put("audio_templates", JSONObject().also { templates ->
        audio.toSortedMap().forEach { (model, text) -> templates.put(model, JSONObject(text)) }
    }).put("audio_recovery_required", JSONArray(audioRecovery.sorted()))

    companion object {
        const val MAX_BYTES = 64 * 1024
        fun parse(json: JSONObject): GalaxyConfiguration {
            require(json.toString().toByteArray().size <= MAX_BYTES)
            val groups = json.getJSONObject("preferences")
            val preferences = groups.keys().asSequence().associateWith { name ->
                require(name in GalaxyConfigurationFields.names)
                val group = groups.getJSONObject(name)
                require(group.length() <= 256)
                group.keys().asSequence().filterNot { name == "xcertplay_airplay" &&
                    it in setOf("display_max_detected_width", "display_max_detected_height") }.associateWith { key ->
                    require(GalaxyConfigurationFields.allowed(name, key))
                    decode(group.getJSONObject(key))
                }
            }
            val audio = json.getJSONObject("audio_templates")
            val templates = audio.keys().asSequence().associateWith { model ->
                require(model in setOf("l7", "l6", "custom"))
                audio.getJSONObject(model).toString().also { com.shilapi.xcertplay.media.AudioRoutingTemplate.parse(it) }
            }
            val recovery = json.optJSONArray("audio_recovery_required") ?: JSONArray()
            require(recovery.length() <= 3)
            val affected = (0 until recovery.length()).map { recovery.getString(it).also { model ->
                require(model in setOf("l7", "l6", "custom"))
            } }.toSet()
            return GalaxyConfiguration(preferences, templates, affected)
        }

        private fun encode(value: Any?): JSONObject = JSONObject().put("type", when (value) {
            null -> "unset"
            is Boolean -> "boolean"
            is Int -> "int"
            is Long -> "long"
            is Float -> "float"
            is String -> "string"
            is Set<*> -> "strings"
            else -> error("CONFIG_VALUE_TYPE")
        }).put("value", when (value) {
            null -> JSONObject.NULL
            is Set<*> -> JSONArray(value.map { it as String }.sorted())
            else -> value
        })

        private fun decode(data: JSONObject): Any? = when (data.getString("type")) {
            "unset" -> null
            "boolean" -> data.getBoolean("value")
            "int" -> data.getInt("value")
            "long" -> data.getLong("value")
            "float" -> data.getDouble("value").toFloat().also { require(it.isFinite()) }
            "string" -> data.getString("value").also { require(it.length <= 8192 && '\u0000' !in it) }
            "strings" -> data.getJSONArray("value").let { array ->
                require(array.length() <= 64)
                (0 until array.length()).map { array.getString(it).also { text -> require(text.length <= 2048) } }.toSet()
            }
            else -> error("CONFIG_VALUE_TYPE")
        }
    }
}

internal data class GalaxyProfile(val id: String, val name: String, val revision: Int,
    val updatedAt: Long, val configuration: GalaxyConfiguration) {
    val model get() = configuration.preferences["l7_audio_templates"]?.get("model") as? String ?: "unconfirmed"
    fun json() = JSONObject().put("schema_version", 1).put("profile_id", id).put("profile_name", name)
        .put("revision", revision).put("updated_at", updatedAt).put("configuration", configuration.json())
    companion object {
        fun parse(text: String): GalaxyProfile {
            require(text.toByteArray().size <= GalaxyConfiguration.MAX_BYTES)
            val data = JSONObject(text)
            require(data.getInt("schema_version") == 1)
            val id = data.getString("profile_id").also { require(it.matches(Regex("[a-z0-9_]{1,64}"))) }
            val name = data.getString("profile_name").also { require(it.isNotBlank() && it.length <= 40 && it.none { c -> c.code < 32 }) }
            val revision = data.getInt("revision").also { require(it > 0) }
            return GalaxyProfile(id, name, revision, data.getLong("updated_at"),
                GalaxyConfiguration.parse(data.getJSONObject("configuration")))
        }
    }
}

/** 草稿和会话快照使用同一读取接口；草稿编辑不写兼容偏好或音频文件。 */
internal class GalaxyConfigurationContext(base: Context, val profile: GalaxyProfile,
    val editable: Boolean = false, private val runtimeOnly: Boolean = false) : ContextWrapper(base) {
    private val groups = GalaxyConfigurationFields.names.associateWith { name ->
        GalaxyScopedPreferences(name, GalaxyMemoryPreferences(profile.configuration.preferences[name].orEmpty().filterKeys { GalaxyConfigurationFields.vehicleAllowed(name, it) }),
            base.getSharedPreferences(name, 0), writeThrough = runtimeOnly)
    }.toMutableMap()
    val audio = profile.configuration.audio.toMutableMap()
    val audioRecovery = profile.configuration.audioRecovery.toMutableSet()
    var onChanged: (() -> Unit)? = null
    override fun getApplicationContext(): Context = this
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        if (name in GalaxyConfigurationFields.names && (!runtimeOnly || name in setOf("xcertplay_airplay", "l7_audio_templates"))) groups.getValue(name)
        else super.getSharedPreferences(name, mode)
    fun configuration() = GalaxyConfiguration(groups.mapValues { (name, prefs) ->
        (profile.configuration.preferences[name].orEmpty().keys + prefs.all.keys).filter { GalaxyConfigurationFields.vehicleAllowed(name, it) }.associateWith { prefs.all[it] }
    }, audio.toMap(), audioRecovery.toSet())
}

internal class GalaxyMemoryPreferences(values: Map<String, Any?>) : SharedPreferences {
    private val values = values.filterValues { it != null }.toMutableMap()
    private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()
    @Synchronized override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    @Synchronized override fun contains(key: String) = values.containsKey(key)
    @Synchronized override fun getString(key: String, defValue: String?) = values[key] as? String ?: defValue
    @Synchronized override fun getStringSet(key: String, defValues: MutableSet<String>?) =
        (values[key] as? Set<*>)?.map { it as String }?.toMutableSet() ?: defValues?.toMutableSet()
    @Synchronized override fun getInt(key: String, defValue: Int) = values[key] as? Int ?: defValue
    @Synchronized override fun getLong(key: String, defValue: Long) = values[key] as? Long ?: defValue
    @Synchronized override fun getFloat(key: String, defValue: Float) = values[key] as? Float ?: defValue
    @Synchronized override fun getBoolean(key: String, defValue: Boolean) = values[key] as? Boolean ?: defValue
    @Synchronized override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) { listeners.add(listener) }
    @Synchronized override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) { listeners.remove(listener) }
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        val changes = mutableMapOf<String, Any?>()
        var clear = false
        override fun putString(key: String, value: String?) = apply { changes[key] = value }
        override fun putStringSet(key: String, value: MutableSet<String>?) = apply { changes[key] = value?.toSet() }
        override fun putInt(key: String, value: Int) = apply { changes[key] = value }
        override fun putLong(key: String, value: Long) = apply { changes[key] = value }
        override fun putFloat(key: String, value: Float) = apply { changes[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value }
        override fun remove(key: String) = apply { changes[key] = null }
        override fun clear() = apply { clear = true }
        override fun apply() { commit() }
        override fun commit(): Boolean {
            val changed: Set<String>
            val observers: List<SharedPreferences.OnSharedPreferenceChangeListener>
            synchronized(this@GalaxyMemoryPreferences) {
                val before = values.toMap()
                if (clear) values.clear()
                changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                changed = (before.keys + values.keys).filter { before[it] != values[it] }.toSet()
                observers = listeners.toList()
            }
            changed.forEach { key -> observers.forEach { it.onSharedPreferenceChanged(this@GalaxyMemoryPreferences, key) } }
            return true
        }
    }
}
