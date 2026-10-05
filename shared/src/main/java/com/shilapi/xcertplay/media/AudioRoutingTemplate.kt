package com.shilapi.xcertplay.media

import org.json.JSONObject
import org.json.JSONTokener

/** 应用自己的路由模板；不能修改原车策略或把音道编号解释为扬声器位置。 */
class AudioRoutingTemplate private constructor(
    val name: String,
    val preferBus: Boolean,
    private val choices: Map<String, Int>,
    private val buses: Map<String, String>,
) {
    fun choice(role: AudioOutputRole): Int = choices.getValue(role.name.lowercase())

    internal fun bus(channel: AudioChannel, input: Boolean): String? =
        if (input) null else buses[channel.name.lowercase()]

    internal fun knownBus(address: String): Boolean = buses.values.any { it.equals(address, true) }

    fun withChoice(role: AudioOutputRole, choice: Int): AudioRoutingTemplate {
        require(AudioOutputPolicy.valid(choice))
        return AudioRoutingTemplate(name, preferBus, choices + (role.name.lowercase() to choice), buses)
    }

    fun withBusEnabled(enabled: Boolean): AudioRoutingTemplate =
        AudioRoutingTemplate(name, enabled, choices, buses)

    fun toJson(): String = JSONObject().apply {
        put("version", 1)
        put("name", name)
        put("preferBus", preferBus)
        put("choices", JSONObject(choices))
        put("outputBuses", JSONObject(buses))
    }.toString(2)

    companion object {
        const val MAX_BYTES = 16 * 1024
        private val roles = setOf("media", "navigation", "assistant")
        private val outputs = roles + setOf("phone", "ringtone")
        private val busPattern = Regex("(?i)bus[a-z0-9_]{1,95}")

        fun system(): AudioRoutingTemplate = AudioRoutingTemplate("L7", false,
            mapOf("media" to 101, "navigation" to 103, "assistant" to 102), emptyMap())

        fun parse(json: String): AudioRoutingTemplate {
            require(json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
            val reader = JSONTokener(json)
            val obj = reader.nextValue() as? JSONObject ?: error("需要 JSON 对象")
            require(reader.nextClean() == 0.toChar())
            require(keys(obj) == setOf("version", "name", "preferBus", "choices", "outputBuses"))
            require(obj.get("version") == 1)
            val name = obj.get("name") as? String ?: error("名称必须是文字")
            require(name.isNotBlank() && name.length <= 64 && name.none { it.isISOControl() })
            val enabled = obj.get("preferBus") as? Boolean ?: error("BUS 开关必须是布尔值")
            val rawChoices = obj.getJSONObject("choices")
            require(keys(rawChoices) == roles)
            val choices = roles.associateWith { role ->
                val value = rawChoices.get(role)
                require(value is Int && AudioOutputPolicy.valid(value))
                value as Int
            }
            val rawBuses = obj.getJSONObject("outputBuses")
            require(keys(rawBuses).all { it in outputs })
            val buses = keys(rawBuses).associateWith { role ->
                val address = rawBuses.get(role) as? String ?: error("BUS 地址必须是文字")
                require(busPattern.matches(address))
                address
            }
            require(!enabled || buses.isNotEmpty())
            return AudioRoutingTemplate(name, enabled, choices, buses)
        }

        private fun keys(obj: JSONObject): Set<String> = obj.keys().asSequence().toSet()
    }
}
