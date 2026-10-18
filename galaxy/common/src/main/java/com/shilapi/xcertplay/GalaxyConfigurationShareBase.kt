package com.shilapi.xcertplay

import org.json.JSONObject

/** 分享格式 2 的固定基线 1（0.1.150）；发布后不随新预设改值，避免旧码恢复出不同配置。 */
internal object GalaxyConfigurationShareBase {
    val ids = listOf("l7", "l7_no_headrest", "l6", "custom")
    fun configuration(id: String): GalaxyConfiguration {
        require(id in ids)
        val data = JSONObject(snapshot)
        val preferences = data.getJSONObject("preferences")
        val main = preferences.getJSONObject("xcertplay_airplay")
        if (id == "l7_no_headrest") main.getJSONObject("audio_focus_enabled").put("value", true)
        if (id == "l6" || id == "custom") {
            main.getJSONObject("display_fps").put("value", 30)
            main.getJSONObject("display_scale_percent").put("value", 100)
            main.getJSONObject("audio_focus_enabled").put("value", true)
            val audio = preferences.getJSONObject("l7_audio_templates")
            audio.getJSONObject("model").put("value", id)
            audio.getJSONObject("mode").put("value", "l7")
            if (id == "custom") audio.getJSONObject("mode_custom").put("value", "custom")
            data.getJSONObject("audio_templates").getJSONObject("l7").remove("focusGains")
        }
        return GalaxyConfiguration.parse(data)
    }
    private const val snapshot = """
{
  "preferences": {
    "carplay_picture": {
    },
    "diplay": {
    },
    "l7_audio_templates": {
      "mode": {"type":"string","value":"custom"},
      "mode_custom": {"type":"string","value":"system"},
      "mode_l6": {"type":"string","value":"l6"},
      "model": {"type":"string","value":"l7"}
    },
    "l7_authentication": {
    },
    "l7_floating_navigation": {
    },
    "l7_remote_log": {
    },
    "l7_ui": {
    },
    "xcertplay_airplay": {
      "adapt_pip_resolution": {"type":"boolean","value":false},
      "adb_cluster_activity_enabled": {"type":"boolean","value":false},
      "advanced_audio_channel_mapping": {"type":"boolean","value":true},
      "assistant_audio_channel": {"type":"int","value":102},
      "audio_focus_enabled": {"type":"boolean","value":false},
      "bluetooth_media_exclusive": {"type":"boolean","value":true},
      "center_map_auto_hide": {"type":"unset","value":null},
      "center_map_follows_dashboard": {"type":"unset","value":null},
      "center_map_overlay": {"type":"unset","value":null},
      "cluster_content": {"type":"unset","value":null},
      "cluster_map_enabled": {"type":"boolean","value":false},
      "cluster_map_scale_percent": {"type":"unset","value":null},
      "cluster_marker_horizontal_step": {"type":"unset","value":null},
      "cluster_marker_vertical_step": {"type":"unset","value":null},
      "cluster_turn_card_overlay_position": {"type":"unset","value":null},
      "cluster_turn_card_overlay_size": {"type":"unset","value":null},
      "cluster_turn_card_overlay_x_percent": {"type":"unset","value":null},
      "cluster_turn_card_overlay_y_percent": {"type":"unset","value":null},
      "display_fps": {"type":"int","value":60},
      "display_physical_size_basis": {"type":"unset","value":null},
      "display_scale_percent": {"type":"int","value":80},
      "display_scale_tenths": {"type":"int","value":10},
      "display_width_physical_mm": {"type":"unset","value":null},
      "galaxy_assistant_buffer_ms": {"type":"int","value":-1},
      "galaxy_media_reporting_channel": {"type":"string","value":"CURRENT"},
      "galaxy_media_reporting_enabled": {"type":"boolean","value":true},
      "galaxy_navigation_buffer_ms": {"type":"int","value":-1},
      "galaxy_navigation_reporting_enabled": {"type":"boolean","value":true},
      "galaxy_p2p_address_mode": {"type":"string","value":"IPV4_ONLY"},
      "galaxy_phone_buffer_ms": {"type":"int","value":-1},
      "galaxy_steering_enabled": {"type":"boolean","value":true},
      "galaxy_video_decoder_custom": {"type":"string","value":"DEFAULT"},
      "galaxy_video_decoder_l6": {"type":"string","value":"C2"},
      "galaxy_video_decoder_l7": {"type":"string","value":"C2"},
      "hevc_enabled": {"type":"boolean","value":false},
      "hevc_software_decoder": {"type":"boolean","value":false},
      "hide_bottom_bar": {"type":"boolean","value":true},
      "hide_top_bar": {"type":"boolean","value":true},
      "l7_audio_bus_enabled": {"type":"boolean","value":false},
      "l7_call_processing_enabled": {"type":"boolean","value":true},
      "location_reporting_enabled": {"type":"boolean","value":false},
      "main_buffered_audio": {"type":"unset","value":null},
      "media_audio_channel": {"type":"int","value":101},
      "media_buffer_ms": {"type":"int","value":300},
      "navigation_audio_channel": {"type":"int","value":103},
      "right_hand_drive": {"type":"boolean","value":false},
      "safe_area_draw_outside": {"type":"boolean","value":true}
    }
  },
  "audio_templates": {
    "custom": {"version":1,"name":"System","preferBus":false,"choices":{"media":101,"navigation":103,"assistant":102,"phone":104},"outputBuses":{}},
    "l6": {"version":1,"name":"L6","preferBus":false,"choices":{"media":101,"navigation":103,"assistant":102,"phone":104},"outputBuses":{},"focusGains":{"media":1,"navigation":3,"assistant":2,"phone":2,"ringtone":3}},
    "l7": {"version":1,"name":"L7","preferBus":false,"choices":{"media":101,"navigation":103,"assistant":102,"phone":104},"outputBuses":{"media":"bus0_media_out","navigation":"bus1_navigation_out","assistant":"bus2_voice_command_out","ringtone":"bus3_call_ring_out","phone":"bus4_call_out"},"focusGains":{"media":1,"navigation":3,"assistant":2,"phone":2,"ringtone":3}}
  },
  "audio_recovery_required": []
}
"""
}
