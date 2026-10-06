package com.shilapi.xcertplay

import android.content.Context
import android.os.Build
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode

/** 产品默认值、升级迁移与限制集中在 Galaxy；沿用原偏好空间和键，不迁移身份。 */
internal object GalaxyPreferencePolicy {
    private const val PREFS = "xcertplay_airplay"
    private const val KEY_ADVANCED_AUDIO_CHANNEL_MAPPING = "advanced_audio_channel_mapping"
    private const val KEY_CALL_PROCESSING_ENABLED = "l7_call_processing_enabled"
    private const val KEY_AUDIO_FOCUS_ENABLED = "audio_focus_enabled"
    private const val KEY_L7_AUDIO_BUS_ENABLED = "l7_audio_bus_enabled"
    private const val KEY_BLUETOOTH_MEDIA_EXCLUSIVE = "bluetooth_media_exclusive"
    private const val KEY_MEDIA_AUDIO_CHANNEL = "media_audio_channel"
    private const val KEY_ASSISTANT_AUDIO_CHANNEL = "assistant_audio_channel"
    private const val KEY_NAVIGATION_AUDIO_CHANNEL = "navigation_audio_channel"
    private const val KEY_NAVIGATION_STREAM_TYPE = "navigation_stream_type"
    private const val KEY_WIRELESS_HOTSPOT_MODE = "wireless_hotspot_mode"
    private const val KEY_MANUAL_HOTSPOT_SSID = "manual_hotspot_ssid"
    private const val KEY_MANUAL_HOTSPOT_PASSPHRASE = "manual_hotspot_passphrase"
    private const val KEY_MANUAL_HOTSPOT_BAND = "manual_hotspot_band"
    private const val KEY_MANUAL_HOTSPOT_CHANNEL = "manual_hotspot_channel"
    private const val KEY_MANUAL_HOTSPOT_SECURITY = "manual_hotspot_security"
    private const val KEY_MEDIA_BUFFER_MS = "media_buffer_ms"
    private const val KEY_CLUSTER_MAP = "cluster_map_enabled"
    private const val KEY_MFI_TARGET = "mfi_target"
    private const val KEY_REMOTE_MFI_SERVER = "remote_mfi_server"
    private const val KEY_REMOTE_MFI_TOKEN = "remote_mfi_token"

    fun loadAdvancedAudioChannelMapping(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ADVANCED_AUDIO_CHANNEL_MAPPING, true)

    fun loadAudioFocusEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUDIO_FOCUS_ENABLED, true)

    fun loadL7AudioBusEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_L7_AUDIO_BUS_ENABLED, false)

    fun saveL7AudioBusEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_L7_AUDIO_BUS_ENABLED, enabled).apply()
    }

    fun restoreUsageAudioDefaults(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUDIO_FOCUS_ENABLED, true)
            .putBoolean(KEY_L7_AUDIO_BUS_ENABLED, false)
            .putBoolean(KEY_ADVANCED_AUDIO_CHANNEL_MAPPING, true)
            .putBoolean(KEY_CALL_PROCESSING_ENABLED, true)
            .putInt(KEY_MEDIA_AUDIO_CHANNEL, 101)
            .putInt(KEY_ASSISTANT_AUDIO_CHANNEL, 102)
            .putInt(KEY_NAVIGATION_AUDIO_CHANNEL, 103)
            .remove(KEY_NAVIGATION_STREAM_TYPE)
            .putInt(KEY_MEDIA_BUFFER_MS, com.shilapi.xcertplay.media.MediaAudioBuffer.DEFAULT_MILLIS)
            .putBoolean("l7_usage_audio_defaults_v1", true)
            .commit()
    }

    fun migrateUsageAudioDefaults(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean("l7_usage_audio_defaults_v1", false)) return
        val editor = prefs.edit().putBoolean("l7_usage_audio_defaults_v1", true)
        if (prefs.getBoolean("l7_boyue_audio_0211", false) &&
            prefs.getInt(KEY_MEDIA_AUDIO_CHANNEL, -1) == 0 &&
            prefs.getInt(KEY_ASSISTANT_AUDIO_CHANNEL, -1) == 0 &&
            prefs.getInt(KEY_NAVIGATION_AUDIO_CHANNEL, -1) == 14) {
            editor.putInt(KEY_MEDIA_AUDIO_CHANNEL, 101)
                .putInt(KEY_ASSISTANT_AUDIO_CHANNEL, 102)
                .putInt(KEY_NAVIGATION_AUDIO_CHANNEL, 103)
        }
        editor.commit()
    }

    fun loadCallProcessingEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CALL_PROCESSING_ENABLED, true)

    fun saveCallProcessingEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_CALL_PROCESSING_ENABLED, enabled).apply()
    }

    fun loadBluetoothMediaExclusive(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_BLUETOOTH_MEDIA_EXCLUSIVE, true)

    fun saveBluetoothMediaExclusive(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_BLUETOOTH_MEDIA_EXCLUSIVE, enabled).apply()
    }

    fun loadMediaAudioChannel(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_MEDIA_AUDIO_CHANNEL, 101)
            .takeIf { com.shilapi.xcertplay.media.AudioOutputPolicy.valid(it) } ?: 0

    fun saveMediaAudioChannel(context: Context, channel: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_MEDIA_AUDIO_CHANNEL, channel.takeIf { com.shilapi.xcertplay.media.AudioOutputPolicy.valid(it) } ?: 0)
            .apply()
    }

    fun loadNavigationAudioChannel(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_NAVIGATION_AUDIO_CHANNEL,
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_NAVIGATION_STREAM_TYPE, 103))
            .takeIf { com.shilapi.xcertplay.media.AudioOutputPolicy.valid(it) } ?: 0

    fun saveNavigationAudioChannel(context: Context, channel: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_NAVIGATION_AUDIO_CHANNEL, channel.takeIf { com.shilapi.xcertplay.media.AudioOutputPolicy.valid(it) } ?: 0)
            .apply()
    }

    fun loadAssistantAudioChannel(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_ASSISTANT_AUDIO_CHANNEL, 102)
            .takeIf { com.shilapi.xcertplay.media.AudioOutputPolicy.valid(it) } ?: 0

    fun saveAssistantAudioChannel(context: Context, channel: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_ASSISTANT_AUDIO_CHANNEL, channel.takeIf {
                com.shilapi.xcertplay.media.AudioOutputPolicy.valid(it)
            } ?: 0).apply()
    }

    fun saveMfiConfiguration(context: Context, target: MfiTarget, server: String? = null, token: String? = null) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MFI_TARGET, target.name)
        if (server != null) editor.putString(KEY_REMOTE_MFI_SERVER, server)
        if (token != null) editor.putString(KEY_REMOTE_MFI_TOKEN, token)
        check(editor.commit())
    }

    fun loadWirelessHotspotMode(context: Context): WirelessHotspotMode {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_WIRELESS_HOTSPOT_MODE, null)
        val mode = WirelessHotspotMode.entries.firstOrNull { it.name == stored }
            ?: WirelessHotspotMode.MANUAL
        val supported = if (mode in setOf(WirelessHotspotMode.LOCAL_ONLY_HOTSPOT, WirelessHotspotMode.EXISTING_WIFI) ||
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && mode == WirelessHotspotMode.WIFI_P2P)
        ) WirelessHotspotMode.MANUAL else mode
        if (stored != supported.name) saveWirelessHotspotMode(context, supported)
        return supported
    }

    fun saveWirelessHotspotMode(context: Context, mode: WirelessHotspotMode) {
        val supported = if (mode in setOf(WirelessHotspotMode.LOCAL_ONLY_HOTSPOT, WirelessHotspotMode.EXISTING_WIFI)) WirelessHotspotMode.MANUAL else mode
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_WIRELESS_HOTSPOT_MODE, supported.name)
            .apply()
    }

    fun saveNativeHotspotCredentials(context: Context, value: com.shilapi.xcertplay.network.NativeHotspotCredentials) {
        require(value.valid())
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MANUAL_HOTSPOT_SSID, value.ssid)
            .putString(KEY_MANUAL_HOTSPOT_PASSPHRASE, value.password)
            .putString(KEY_MANUAL_HOTSPOT_SECURITY, value.security.name)
            .putString(KEY_MANUAL_HOTSPOT_BAND, ManualHotspotBand.AUTO.name)
            .putInt(KEY_MANUAL_HOTSPOT_CHANNEL, 0)
            .apply()
    }

    fun loadClusterMapEnabled(context: Context): Boolean =
        com.shilapi.xcertplay.l7.L7VehiclePolicy.BYD_FEATURES_ENABLED &&
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CLUSTER_MAP, false)
}
