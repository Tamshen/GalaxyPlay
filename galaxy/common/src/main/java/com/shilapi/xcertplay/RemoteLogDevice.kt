package com.shilapi.xcertplay

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/** 日志名称由车机厂商、型号和设备哈希组成，原始唯一标识不落盘、不写日志。 */
internal object RemoteLogDevice {
    private val format = Regex("[a-z0-9]+(?:_[a-z0-9]+)*_([a-f0-9]{5}(?:_[a-f0-9]{5}){3})")
    fun validName(value: String) = value.length <= 72 && format.matches(value)

    // 普通 Android 10/11 应用通常无法读取序列号；不额外申请权限，失败即回退。
    @SuppressLint("MissingPermission", "HardwareIds")
    @Synchronized fun id(
        context: Context,
        serial: () -> String? = { Build.getSerial() },
        androidId: () -> String? = { Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) },
        manufacturer: String = Build.MANUFACTURER,
        model: String = Build.MODEL,
    ): String {
        val prefs = context.getSharedPreferences("l7_log_device", Context.MODE_PRIVATE)
        prefs.getString("id", null)?.let { saved ->
            // 型号前缀可更新；覆盖升级始终保留原设备哈希，包括随机回退值。
            val normalized = saved.lowercase(Locale.ROOT).replace('-', '_')
            format.matchEntire(normalized)?.groupValues?.get(1)?.let { hash ->
                val name = "${modelPrefix(manufacturer, model)}_$hash"
                if (name != saved) prefs.edit().putString("id", name).apply()
                return name
            }
        }
        val scoped = runCatching { androidId() }.getOrNull()?.takeIf(::usable)
        val hardware = if (scoped == null) runCatching { serial() }.getOrNull()?.takeIf(::usable) else null
        val (source, value) = when {
            scoped != null -> "android_id" to scoped
            hardware != null -> "serial" to hardware
            else -> "installation" to UUID.randomUUID().toString()
        }
        val id = derive(source, value.trim(), manufacturer, model)
        // apply 立即更新进程内状态；与服务器配置分开保存，恢复默认值不改变编号。
        prefs.edit().putString("id", id).apply()
        return id
    }

    private fun usable(value: String): Boolean {
        val text = value.trim().lowercase(Locale.ROOT)
        return text.isNotEmpty() && text !in setOf("unknown", "null", "9774d56d682e549c") &&
            !text.all { it == '0' } && text.none { it.code < 32 || it.code == 127 }
    }

    internal fun modelPrefix(manufacturer: String, model: String): String = listOf(manufacturer, model)
        .map(String::trim).filter { it.isNotEmpty() && it.lowercase(Locale.ROOT) !in setOf("unknown", "null") }
        .joinToString("_").lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "_")
        .trim('_').take(48).trimEnd('_').ifEmpty { "head_unit" }

    internal fun derive(source: String, value: String,
                        manufacturer: String = Build.MANUFACTURER, model: String = Build.MODEL): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("com.ecarx.carplay/log-device/v1\u0000$source\u0000$value".toByteArray(Charsets.UTF_8))
        val hex = bytes.take(10).joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
        return modelPrefix(manufacturer, model) + "_" + hex.chunked(5).joinToString("_")
    }
}
