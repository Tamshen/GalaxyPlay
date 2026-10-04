package com.shilapi.xcertplay

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/** 只保存和上传日志用哈希编号，原始硬件标识不落盘、不写日志。 */
internal object RemoteLogDevice {
    private val format = Regex("L7-[A-F0-9]{5}(?:-[A-F0-9]{5}){3}")

    // 普通 Android 10/11 应用通常无法读取序列号；不额外申请权限，失败即回退。
    @SuppressLint("MissingPermission", "HardwareIds")
    @Synchronized fun id(
        context: Context,
        serial: () -> String? = { Build.getSerial() },
        androidId: () -> String? = { Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) },
    ): String {
        val prefs = context.getSharedPreferences("l7_log_device", Context.MODE_PRIVATE)
        prefs.getString("id", null)?.takeIf { format.matches(it) }?.let { return it }
        val scoped = runCatching { androidId() }.getOrNull()?.takeIf(::usable)
        val hardware = if (scoped == null) runCatching { serial() }.getOrNull()?.takeIf(::usable) else null
        val (source, value) = when {
            scoped != null -> "android_id" to scoped
            hardware != null -> "serial" to hardware
            else -> "installation" to UUID.randomUUID().toString()
        }
        val id = derive(source, value.trim())
        // apply 立即更新进程内状态；与服务器配置分开保存，恢复默认值不改变编号。
        prefs.edit().putString("id", id).apply()
        return id
    }

    private fun usable(value: String): Boolean {
        val text = value.trim().lowercase(Locale.ROOT)
        return text.isNotEmpty() && text !in setOf("unknown", "null", "9774d56d682e549c") &&
            !text.all { it == '0' } && text.none { it.code < 32 || it.code == 127 }
    }

    internal fun derive(source: String, value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("com.ecarx.carplay/log-device/v1\u0000$source\u0000$value".toByteArray(Charsets.UTF_8))
        val hex = bytes.take(10).joinToString("") { "%02X".format(Locale.ROOT, it.toInt() and 0xff) }
        return "L7-" + hex.chunked(5).joinToString("-")
    }
}
