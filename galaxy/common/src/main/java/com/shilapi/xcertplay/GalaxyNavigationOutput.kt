package com.shilapi.xcertplay

import android.content.Context
import android.media.AudioManager
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.NavigationOutputDevice
import org.json.JSONObject

/** 独立车型偏好，不改模板、传统流或系统路由；草稿确认后保存。 */
internal object GalaxyNavigationOutput {
    private fun key(context: Context) = "galaxy_navigation_output_" + L7AudioTemplates.model(context).name
    fun load(context: Context): NavigationOutputDevice? = runCatching {
        val raw = context.getSharedPreferences("xcertplay_airplay", 0).getString(key(context), null) ?: return null
        val data = JSONObject(raw)
        NavigationOutputDevice(data.getInt("id"), data.getInt("type"), data.getString("address"), data.getString("name"))
    }.getOrNull()
    internal fun save(context: Context, device: NavigationOutputDevice?, expectedModel: L7AudioTemplates.Model = L7AudioTemplates.model(context)) {
        check(L7AudioTemplates.model(context) == expectedModel)
        val previous = context.getSharedPreferences("xcertplay_airplay", 0).getString(key(context), null)
        val editor = context.getSharedPreferences("xcertplay_airplay", 0).edit()
        if (device == null) editor.remove(key(context)) else editor.putString(key(context), JSONObject()
            .put("id", device.id).put("type", device.type).put("address", device.address).put("name", device.name).toString())
        if (!editor.commit()) {
            context.getSharedPreferences("xcertplay_airplay", 0).edit().putString(key(context), previous).apply()
            error("NAVIGATION_DEVICE_SAVE_FAILED")
        }
    }
    fun add(context: Context, parent: LinearLayout) {
        lateinit var row: L7SettingRow
        fun name(value: NavigationOutputDevice?) = value?.let { "${it.name} (${it.type}, ${it.id})" }
            ?: context.getString(R.string.galaxy_navigation_device_auto)
        row = L7Components.valueRow(context, context.getString(R.string.galaxy_navigation_device), name(load(context))) {
            val devices = runCatching { context.getSystemService(AudioManager::class.java)
                ?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.filter { it.isSink }?.map {
                    NavigationOutputDevice(it.id, it.type, it.address, it.productName.toString())
                }.orEmpty() }.getOrElse { emptyList() }
            val expectedModel = L7AudioTemplates.model(context)
            val stored = load(context)
            val selected = stored?.match(devices) ?: stored
            val options = listOf<NavigationOutputDevice?>(null) + listOfNotNull(selected?.takeIf { it !in devices }) + devices
            L7Components.select(context, context.getString(R.string.galaxy_navigation_device), options.map(::name),
                options.indexOf(selected).coerceAtLeast(0), context.getString(R.string.l7_save_next_connection)) { index ->
                try { save(context, options[index], expectedModel); row.setValue(name(options[index])) }
                catch (_: Exception) { android.widget.Toast.makeText(context, R.string.l7_template_save_failed, android.widget.Toast.LENGTH_LONG).show() }
            }
        }
        parent.addView(row)
        parent.addView(L7SettingRow(context, context.getString(R.string.galaxy_navigation_device_note_title), context.getString(R.string.galaxy_navigation_device_note)))
    }
}
