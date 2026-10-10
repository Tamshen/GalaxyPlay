package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.host.R

/** 可编辑字段复用现有用户文案；未显示的兼容参数仍完整保存到配置文件。 */
internal object GalaxyConfigurationFields {
    data class Field(val group: Int, val space: String, val key: String, val title: Int,
        val default: Any?, val choices: List<Any> = emptyList(), val labels: List<Int> = emptyList(),
        val sensitive: Boolean = false, val minimum: Int? = null, val maximum: Int? = null)
    val names = setOf("xcertplay_airplay", "diplay", "l7_ui", "l7_floating_navigation",
        "l7_audio_templates", "l7_authentication", "l7_remote_log", "carplay_picture")
    private const val MAIN = "xcertplay_airplay"
    val fields = listOf(
        Field(R.string.l7_section_projection, MAIN, "display_scale_tenths", R.string.resolution, 10,
            listOf(10, 8, 6), listOf(R.string.resolution_native, R.string.s_80_lighter_load, R.string.s_60_lightest_load)),
        Field(R.string.l7_section_projection, MAIN, "display_fps", R.string.frame_rate, 30,
            listOf(30, 60), listOf(R.string.s_30_fps_lighter_load, R.string.s_60_fps_smoother_motion)),
        Field(R.string.l7_section_projection, MAIN, "hevc_enabled", R.string.efficient_video, false),
        Field(R.string.l7_section_projection, MAIN, "hevc_software_decoder", R.string.profile_software_hevc, false),
        Field(R.string.l7_section_projection, MAIN, "hide_top_bar", R.string.profile_hide_top, true),
        Field(R.string.l7_section_projection, MAIN, "hide_bottom_bar", R.string.profile_hide_bottom, true),
        Field(R.string.l7_section_projection, MAIN, "right_hand_drive", R.string.profile_right_hand, false),
        Field(R.string.l7_section_app_ui, "l7_ui", "density", R.string.l7_ui_size, 280, minimum = 160, maximum = 480),
        Field(R.string.l7_section_app_ui, "diplay", "app_language", R.string.language_app_language, "system",
            listOf("system", "zh", "en"), listOf(R.string.language_system_default, R.string.profile_chinese, R.string.profile_english)),
        Field(R.string.l7_section_app_ui, MAIN, "carplay_night_mode", R.string.profile_night_mode, "system",
            listOf("system", "day", "night", "ambient"), listOf(R.string.profile_follow_system, R.string.profile_day, R.string.profile_night, R.string.profile_ambient)),
        Field(R.string.l7_section_app_ui, MAIN, "ambient_delay_seconds", R.string.profile_ambient_delay, 2, minimum = 0, maximum = 60),
        Field(R.string.l7_section_floating, "l7_floating_navigation", "desktop", R.string.l7_desktop_title, true),
        Field(R.string.l7_section_floating, "l7_floating_navigation", "transparency", R.string.l7_floating_transparency, 50,
            listOf(0, 25, 50, 75)),
        Field(R.string.automatic_connection, "diplay", "auto_connect", R.string.connect_when_diplay_opens, false),
        Field(R.string.automatic_connection, MAIN, "auto_start_on_boot", R.string.open_after_the_car_starts, false),
        Field(R.string.automatic_connection, MAIN, "wireless_enabled", R.string.profile_wireless_default, true),
        Field(R.string.config_page_steering, MAIN, "galaxy_steering_enabled", R.string.config_steering_control, true),
        Field(R.string.config_page_reporting, MAIN, "galaxy_media_reporting_enabled", R.string.config_media_reporting, true),
        Field(R.string.config_page_reporting, MAIN, "galaxy_navigation_reporting_enabled", R.string.config_navigation_reporting, true),
        Field(R.string.config_page_reporting, MAIN, "location_reporting_enabled", R.string.report_location_to_iphone, false),
        Field(R.string.l7_start_wireless, MAIN, "manual_hotspot_ssid", R.string.profile_hotspot_name, "", sensitive = true),
        Field(R.string.l7_start_wireless, MAIN, "manual_hotspot_passphrase", R.string.profile_hotspot_password, "", sensitive = true),
        Field(R.string.l7_start_wireless, MAIN, "manual_hotspot_security", R.string.profile_hotspot_security, "OPEN",
            listOf("OPEN", "WPA2", "WPA3_TRANSITION", "WPA3")),
        Field(R.string.l7_start_wireless, MAIN, "manual_hotspot_band", R.string.profile_hotspot_band, "AUTO", listOf("AUTO", "GHZ_2_4", "GHZ_5")),
        Field(R.string.l7_start_wireless, MAIN, "manual_hotspot_channel", R.string.profile_hotspot_channel, 0, minimum = 0, maximum = 196),
        Field(R.string.l7_auth_title, MAIN, "mfi_target", R.string.l7_auth_choose_source, "LOCAL",
            listOf("LOCAL", "USB_CH341", "REMOTE", "I2C"), listOf(R.string.l7_auth_builtin, R.string.l7_auth_usb, R.string.l7_auth_remote, R.string.profile_i2c)),
        Field(R.string.l7_auth_title, MAIN, "remote_mfi_server", R.string.profile_mfi_server, ""),
        Field(R.string.l7_auth_title, MAIN, "remote_mfi_token", R.string.profile_mfi_token, "", sensitive = true),
        Field(R.string.l7_logs_title, MAIN, "debug_logs_enabled", R.string.profile_debug_overlay, false),
        Field(R.string.l7_logs_title, "l7_remote_log", "endpoint", R.string.profile_log_server, ""),
        Field(R.string.l7_logs_title, "l7_remote_log", "authorization", R.string.profile_log_authorization, "", sensitive = true),
        Field(R.string.l7_section_app_ui, MAIN, "ambient_lux_threshold", R.string.profile_ambient_lux, 30, minimum = 1, maximum = 200000),
        Field(R.string.l7_section_projection, MAIN, "display_scale_percent", R.string.profile_resolution_percent, 100, minimum = 30, maximum = 100),
        Field(R.string.l7_section_app_ui, MAIN, "ui_scale_percent", R.string.profile_projection_ui, 100, minimum = 50, maximum = 200),
        Field(R.string.l7_section_projection, MAIN, "safe_area_draw_outside", R.string.profile_draw_outside, true),
        Field(R.string.l7_section_projection, MAIN, "adapt_pip_resolution", R.string.profile_pip, false),
    )

    fun allowed(space: String, key: String): Boolean {
        if (space !in names || key.length > 120) return false
        return when (space) {
            "diplay" -> key in setOf("auto_connect", "app_language")
            "l7_authentication" -> false
            "l7_audio_templates" -> key in setOf("model", "mode", "mode_l6", "mode_custom")
            "l7_ui" -> key == "density"
            "l7_remote_log" -> key in setOf("endpoint", "authorization")
            "l7_floating_navigation" -> key in setOf("desktop", "transparency", "x", "y")
            "carplay_picture" -> key in CarPlayPicture.keys
            else -> !Regex("(?i)identity|private|certificate|lockdown|pairing|uuid|device_id|consent|sharing|display_max_detected|l7_usage_audio_defaults|l7_boyue_audio").containsMatchIn(key)
        }
    }

    // 明确列出车型参数，新增连接或应用偏好不能意外进入模板覆盖范围。
    private val vehicleKeys = setOf(
        "display_scale_tenths", "display_scale_percent", "display_fps",
        "hevc_enabled", "hevc_software_decoder", "hide_top_bar", "hide_bottom_bar", "right_hand_drive",
        "safe_area_draw_outside", "adapt_pip_resolution", "display_width_physical_mm", "display_physical_size_basis",
        "galaxy_video_decoder_l7", "galaxy_video_decoder_l6", "galaxy_video_decoder_custom",
        "media_buffer_ms", "main_buffered_audio", "advanced_audio_channel_mapping", "audio_focus_enabled",
        "l7_audio_bus_enabled", "l7_call_processing_enabled", "bluetooth_media_exclusive",
        "media_audio_channel", "navigation_audio_channel", "navigation_stream_type", "assistant_audio_channel",
        "galaxy_navigation_output_L7", "galaxy_navigation_output_L6", "galaxy_navigation_output_CUSTOM",
        "galaxy_steering_enabled", "galaxy_media_reporting_enabled", "galaxy_navigation_reporting_enabled",
        "location_reporting_enabled", "cluster_map_enabled", "adb_cluster_activity_enabled",
        "center_map_overlay", "center_map_auto_hide", "center_map_follows_dashboard", "cluster_map_scale_percent",
        "cluster_content", "cluster_marker_horizontal_step", "cluster_marker_vertical_step",
        "cluster_turn_card_overlay_position", "cluster_turn_card_overlay_size", "cluster_turn_card_overlay_x_percent",
        "cluster_turn_card_overlay_y_percent", "cluster_turn_card_overlay_size_percent", "cluster_turn_card_opacity_percent",
        "cluster_safe_area_1920x720")
    fun vehicleAllowed(space: String, key: String): Boolean = allowed(space, key) && when (space) {
        "l7_audio_templates" -> true
        MAIN -> key in vehicleKeys || key.matches(Regex("safe_area_[0-9]{1,5}x[0-9]{1,5}"))
        else -> false
    }

    fun defaults(context: Context, vehicleOnly: Boolean = true): Map<String, Map<String, Any?>> {
        val groups = names.associateWith { mutableMapOf<String, Any?>() }
        fields.forEach { groups.getValue(it.space)[it.key] = it.default }
        groups.getValue(MAIN).putAll(mapOf("wireless_hotspot_mode" to "MANUAL", "media_buffer_ms" to 300,
            "advanced_audio_channel_mapping" to true, "audio_focus_enabled" to true,
            "l7_audio_bus_enabled" to false, "l7_call_processing_enabled" to true,
            "bluetooth_media_exclusive" to true, "media_audio_channel" to 101,
            "navigation_audio_channel" to 103, "assistant_audio_channel" to 102,
            "galaxy_video_decoder_l7" to "C2", "galaxy_video_decoder_l6" to "C2",
            "galaxy_video_decoder_custom" to "DEFAULT", "display_scale_percent" to 100,
            "ui_scale_percent" to 100, "ambient_lux_threshold" to 30,
            "safe_area_draw_outside" to true, "adapt_pip_resolution" to false,
            "cluster_map_enabled" to false, "adb_cluster_activity_enabled" to false,
            "wifi_p2p_preferred_channel" to 0, "existing_wifi_ssid" to "", "existing_wifi_passphrase" to "",
            "manufacturer" to AirPlayPersistence.DEFAULT_MANUFACTURER, "model" to AirPlayPersistence.DEFAULT_MODEL,
            "oem_label" to AirPlayPersistence.DEFAULT_OEM_LABEL, "mfi_i2c_path" to AirPlayPersistence.DEFAULT_MFI_I2C_PATH))
        // 动态几何和未开启的上游功能保留“未设置”，读取时仍由原有策略计算默认值。
        listOf("main_buffered_audio", "center_map_overlay", "center_map_auto_hide", "cluster_map_scale_percent",
            "cluster_content", "cluster_marker_horizontal_step", "cluster_marker_vertical_step",
            "display_width_physical_mm", "display_physical_size_basis",
            "cluster_turn_card_overlay_position", "cluster_turn_card_overlay_size", "cluster_turn_card_overlay_x_percent",
            "cluster_turn_card_overlay_y_percent", "center_map_follows_dashboard", "settings_gesture_fingers").forEach {
            groups.getValue(MAIN).putIfAbsent(it, null)
        }
        groups.getValue("l7_audio_templates").putAll(mapOf("mode" to "l7", "mode_l6" to "l6", "mode_custom" to "system"))
        groups.getValue("l7_floating_navigation").putAll(mapOf("x" to 0f, "y" to .45f))
        CarPlayPicture.keys.forEach { groups.getValue("carplay_picture")[it] = CarPlayPicture.defaultValue(it) }
        groups.getValue("l7_remote_log")["endpoint"] = context.getString(R.string.l7_log_default_url)
        groups.getValue("l7_remote_log")["authorization"] = context.getString(R.string.l7_log_default_authorization)
        return groups.mapValues { (space, values) -> values.filterKeys { !vehicleOnly || vehicleAllowed(space, it) } }
    }

    fun capture(context: Context): GalaxyConfiguration {
        val groups = defaults(context).mapValues { (_, values) -> values.toMutableMap() }
        names.forEach { name -> context.getSharedPreferences(name, 0).all.forEach { (key, value) ->
            if (vehicleAllowed(name, key)) groups.getValue(name)[key] = value
        } }
        val audio = mutableMapOf<String, String>()
        val recovery = mutableSetOf<String>()
        if (!context.getSharedPreferences(MAIN, 0).contains("display_scale_percent"))
            groups.getValue(MAIN)["display_scale_percent"] = AirPlayPersistence.loadDisplayScalePercent(context)
        L7AudioTemplates.Model.entries.forEach { model ->
            val file = java.io.File(context.filesDir, audioFile(model.id))
            val atomic = android.util.AtomicFile(file)
            val old = if (file.exists() || java.io.File(file.path + ".bak").exists())
                runCatching { atomic.openRead().use { L7AudioTemplates.parse(it).toJson() } }
                    .onFailure { recovery += model.id }.getOrNull() else null
            audio[model.id] = old ?: context.assets.open("audio-templates/${if (model.id == "custom") "system" else model.id}.json")
                .use { L7AudioTemplates.parse(it).toJson() }
        }
        return GalaxyConfiguration(groups, audio, recovery)
    }

    fun factory(context: Context, model: String): GalaxyConfiguration {
        require(model in setOf("l7", "l6", "custom"))
        val groups = defaults(context).mapValues { (_, value) -> value.toMutableMap() }
        groups.getValue("l7_audio_templates")["model"] = model
        groups.getValue("l7_audio_templates")[if (model == "l7") "mode" else "mode_$model"] = if (model == "custom") "custom" else model
        val templates = L7AudioTemplates.Model.entries.associate { entry -> entry.id to
            context.assets.open("audio-templates/${if (entry.id == "custom") "system" else entry.id}.json")
                .use { L7AudioTemplates.parse(it).toJson() } }
        return GalaxyConfiguration(groups, templates)
    }

    fun audioFile(model: String) = if (model == "l7") "audio-template.json" else "audio-template-$model.json"

    fun validate(configuration: GalaxyConfiguration) {
        fields.forEach { field ->
            val value = configuration.preferences[field.space]?.get(field.key) ?: return@forEach
            require(value.javaClass == field.default?.javaClass)
            if (field.choices.isNotEmpty()) require(value in field.choices)
            if (value is Int && field.minimum != null) require(value in field.minimum..field.maximum!!)
        }
        val main = configuration.preferences[MAIN].orEmpty()
        if (main["mfi_target"] == "REMOTE")
            L7Authentication.validateRemote(main["remote_mfi_server"] as? String ?: "", main["remote_mfi_token"] as? String ?: "")
        val groups = configuration.preferences["l7_remote_log"].orEmpty()
        val endpoint = groups["endpoint"] as? String ?: ""
        val authorization = groups["authorization"] as? String ?: ""
        if (endpoint.isNotEmpty()) require(RemoteLogConfig.validEndpoint(endpoint))
        if (authorization.isNotEmpty()) require(RemoteLogConfig.validAuthorization(authorization))
    }
}
