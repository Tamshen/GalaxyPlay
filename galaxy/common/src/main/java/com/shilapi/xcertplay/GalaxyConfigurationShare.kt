package com.shilapi.xcertplay

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater

/** 文件保留完整参数，二维码使用固定基线差异；两者恢复同一配置，不包含连接凭据。 */
internal data class GalaxyConfigurationShare(val name: String, val configuration: GalaxyConfiguration,
    val template: String = "custom", val remark: String = "") {
    fun json(): String = JSONObject().put("format", FORMAT).put("version", 1).put("name", name)
        .put("template", template).put("configuration", configuration.json())
        .also { if (remark.isNotEmpty()) it.put("remark", remark) }.toString()

    fun code(): String {
        val bytes = GalaxyConfigurationShareCompact.encode(this).toByteArray(Charsets.UTF_8)
        require(bytes.size <= GalaxyConfiguration.MAX_BYTES)
        val compressor = Deflater(Deflater.BEST_COMPRESSION)
        return try {
            compressor.setInput(bytes); compressor.finish()
            val result = ByteArrayOutputStream()
            val buffer = ByteArray(2048)
            while (!compressor.finished()) {
                val count = compressor.deflate(buffer)
                check(count > 0)
                result.write(buffer, 0, count)
            }
            PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(result.toByteArray())
        } finally { compressor.end() }
    }

    companion object {
        const val PREFIX = "GALAXYPLAY2:"
        const val LEGACY_PREFIX = "GALAXYPLAY1:"
        const val MAX_TEXT = 96 * 1024
        const val MAX_REMARK = 200
        private const val FORMAT = "galaxyplay-vehicle"

        fun from(profile: GalaxyProfile, remark: String = ""): GalaxyConfigurationShare {
            val shared = GalaxyConfigurationShare(profile.name, GalaxyApplicationPreferences.vehicle(profile.configuration), profile.template, remark.trim())
            return parse(shared.json())
        }

        fun parse(text: String): GalaxyConfigurationShare {
            require(text.length <= MAX_TEXT)
            val input = text.trim()
            val prefix = listOf(PREFIX, LEGACY_PREFIX).firstOrNull { input.startsWith(it) }
            val json = if (prefix != null) inflate(input.substring(prefix.length)) else input
            require(json.toByteArray(Charsets.UTF_8).size <= GalaxyConfiguration.MAX_BYTES)
            val data = JSONObject(json)
            require(data.getString("format") == FORMAT && data.getInt("version") in 1..2)
            val name = data.getString("name").trim()
            require(name.isNotEmpty() && name.length <= 40 && name.none { it.code < 32 })
            val remark = if (data.has("remark")) (data.get("remark") as? String ?: error("CONFIG_REMARK_TYPE")).trim() else ""
            require(remark.length <= MAX_REMARK && remark.none { it.code < 32 && it != '\n' })
            val template = data.getString("template")
            require(GalaxyVehicleTemplates.find(template) != null)
            val configuration = if (data.getInt("version") == 2) GalaxyConfigurationShareCompact.decode(data)
                else GalaxyConfiguration.parse(data.getJSONObject("configuration"))
            configuration.preferences.forEach { (space, values) ->
                require(values.keys.all { GalaxyConfigurationFields.vehicleAllowed(space, it) })
            }
            require(configuration.audioRecovery.isEmpty())
            require(configuration.preferences["l7_audio_templates"]?.get("model") in setOf("l7", "l6", "custom"))
            require(configuration.audio.keys.containsAll(setOf("l7", "l6", "custom")))
            GalaxyConfigurationFields.validate(configuration)
            return GalaxyConfigurationShare(name, configuration, template, remark)
        }

        private fun inflate(encoded: String): String {
            val inflater = Inflater()
            return try {
                inflater.setInput(Base64.getUrlDecoder().decode(encoded))
                val result = ByteArrayOutputStream()
                val buffer = ByteArray(2048)
                while (!inflater.finished()) {
                    val count = inflater.inflate(buffer)
                    require(count > 0 && result.size() + count <= GalaxyConfiguration.MAX_BYTES)
                    result.write(buffer, 0, count)
                }
                require(inflater.remaining == 0)
                result.toString(Charsets.UTF_8.name())
            } finally { inflater.end() }
        }
    }
}
