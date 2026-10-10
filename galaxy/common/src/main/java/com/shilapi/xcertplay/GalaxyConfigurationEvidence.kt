package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.host.R
import org.json.JSONObject
import java.security.MessageDigest

/** 上传副本只含参数与设置状态；自由文本、凭据和设备地址默认遮盖。 */
internal class GalaxyConfigurationEvidence private constructor(val id: String, val text: String) {
    val header get() = PREFIX + text
    override fun equals(other: Any?) = other is GalaxyConfigurationEvidence && id == other.id && text == other.text
    override fun hashCode() = text.hashCode()
    companion object {
        const val PREFIX = "CONFIG_HEADER "
        const val MAX_BYTES = 32 * 1024
        private val publicStrings = setOf("app_language", "model", "mode", "mode_l6", "mode_custom", "source",
            "wireless_hotspot_mode", "manual_hotspot_security", "manual_hotspot_band", "mfi_target",
            "carplay_night_mode", "cluster_content", "cluster_turn_card_overlay_position", "display_physical_size_basis")
        private val enums = setOf("system", "zh", "en", "l7", "l6", "custom", "l7-bus", "day", "night", "ambient",
            "BUILT_IN", "TEXT", "LOCAL", "USB", "USB_CH341", "I2C", "REMOTE", "MANUAL", "WIFI_P2P", "LOCAL_ONLY",
            "OPEN", "WPA2", "WPA3", "WPA3_TRANSITION", "AUTO", "GHZ_2_4", "GHZ_5", "MAP", "INSTRUMENTS",
            "DEFAULT", "C2", "OMX")
        fun capture(context: Context): GalaxyConfigurationEvidence = from(context,
            (context as? GalaxyConfigurationContext)?.profile ?: GalaxyProfiles(context).refresh())
        fun from(context: Context, profile: GalaxyProfile): GalaxyConfigurationEvidence {
            val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
            val data = profile.json().put("profile_name", "[redacted]")
                .put("model", profile.model).put("app_version", version.take(80))
                .put("core_version", context.getString(R.string.l7_core_source_info).take(120))
                .put("application_preferences", GalaxyApplicationPreferences.capture(context))
            return sanitize(data)
        }
        /** 日志文件也视为不可信输入：回读必须重新按字段遮盖，不能绕过常规脱敏。 */
        fun read(text: String): GalaxyConfigurationEvidence {
            require(text.toByteArray().size <= MAX_BYTES)
            val input = JSONObject(text)
            val expected = input.getString("config_id")
            val output = sanitize(input)
            require(output.id == expected)
            return output
        }
        private fun sanitize(input: JSONObject): GalaxyConfigurationEvidence {
            val profile = GalaxyProfile.parse(input.toString())
            val data = profile.json().put("profile_name", "[redacted]")
            val groups = data.getJSONObject("configuration").getJSONObject("preferences")
            sanitizePreferences(groups, input.optJSONObject("configuration")?.optJSONObject("preferences"))
            if (input.has("application_preferences")) {
                val original = input.getJSONObject("application_preferences")
                val application = GalaxyApplicationPreferences.read(original)
                sanitizePreferences(application, original)
                data.put("application_preferences", application)
            }
            val audio = data.getJSONObject("configuration").getJSONObject("audio_templates")
            audio.keys().forEach { audio.getJSONObject(it).put("name", "[redacted]") }
            data.put("model", profile.model.takeIf { it in setOf("l7", "l6", "custom") } ?: "unknown")
                .put("app_version", DiagnosticRedactor.redact(input.optString("app_version"))?.take(80).orEmpty())
                .put("core_version", DiagnosticRedactor.redact(input.optString("core_version"))?.take(120).orEmpty())
            val digest = MessageDigest.getInstance("SHA-256").digest(data.toString().toByteArray())
            val id = "cfg_" + digest.joinToString("") { byte ->
                "${('g'.code + ((byte.toInt() and 255) shr 4)).toChar()}${('g'.code + (byte.toInt() and 15)).toChar()}"
            }
            val text = data.put("config_id", id).toString()
            require(text.toByteArray().size <= MAX_BYTES)
            return GalaxyConfigurationEvidence(id, text)
        }
        private fun sanitizePreferences(groups: JSONObject, original: JSONObject?) {
            groups.keys().forEach { space ->
                val group = groups.getJSONObject(space)
                group.keys().forEach { key ->
                    val item = group.getJSONObject(key)
                    val value = item.opt("value")
                    if (value is String || value is org.json.JSONArray) {
                        val allowed = value is String && value in enums &&
                            (key in publicStrings && !(space == "xcertplay_airplay" && key == "model") || key.startsWith("galaxy_video_decoder_"))
                        if (!allowed) {
                            if (key.startsWith("galaxy_navigation_output_") && value is String && value != "[redacted]") {
                                val device = runCatching { JSONObject(value) }.getOrNull()
                                item.put("value", if (device == null) "[redacted]" else JSONObject()
                                    .put("id", device.optInt("id")).put("type", device.optInt("type"))
                                    .put("address", "[redacted]").put("name", "[redacted]").toString())
                            } else if (key.startsWith("safe_area_") && value is String && value.matches(Regex("[0-9, :x-]{1,100}"))) {
                                item.put("value", value)
                            } else {
                                item.put("value", if (value is org.json.JSONArray) org.json.JSONArray().put("[redacted]") else "[redacted]")
                            }
                            // 未设置与已设置可区分；再次脱敏不改变已有状态。
                            val wasSet = original?.optJSONObject(space)?.optJSONObject(key)?.optBoolean("is_set", value.toString().isNotEmpty())
                            item.put("is_set", wasSet ?: value.toString().isNotEmpty())
                        }
                    }
                }
            }
        }
    }
}
