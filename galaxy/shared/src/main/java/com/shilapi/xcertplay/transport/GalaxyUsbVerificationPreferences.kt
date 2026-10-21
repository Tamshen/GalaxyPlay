package com.shilapi.xcertplay.transport

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/** 将旧命名空间中的选择与恢复事务迁入功能名称；提交成功后才清除旧记录。 */
object GalaxyUsbVerificationPreferences {
    const val KEY = "galaxy_usb_verification_enabled"
    const val JOURNAL = "galaxy_usb_verification_recovery"
    private val legacySelection = Regex("galaxy_[a-z0-9]+_usb_experimental")
    private val recoveryKeys = listOf("original", "controller", "carplay")

    @Synchronized fun enabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
        if (prefs.contains(KEY)) return prefs.getBoolean(KEY, false)
        val old = prefs.all.filter { (key, value) -> legacySelection.matches(key) && value is Boolean }
        if (old.isEmpty()) return false
        val enabled = old.values.all { it == true }
        val edit = prefs.edit().putBoolean(KEY, enabled)
        old.keys.forEach(edit::remove)
        edit.commit()
        return enabled
    }

    @Synchronized fun journal(context: Context): SharedPreferences {
        val current = context.getSharedPreferences(JOURNAL, Context.MODE_PRIVATE)
        val directory = File(context.applicationInfo.dataDir, "shared_prefs")
        val files = directory.listFiles()
        if (directory.exists() && files == null) fail()
        // 旧版文件使用带来源段的命名空间；按固定后缀识别，包括 Android 的恢复备份。
        val names = files.orEmpty().mapNotNull { file -> when {
            file.name.endsWith(".xml.bak") -> file.name.removeSuffix(".xml.bak")
            file.name.endsWith(".xml") -> file.name.removeSuffix(".xml")
            else -> null
        } }.filter { it != JOURNAL && it.startsWith("galaxy_") && it.endsWith("_usb_recovery") }.distinct()
        for (name in names) {
            val old = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            if (!old.contains("original")) continue
            val values = recoveryKeys.associateWith { old.getString(it, null) }
            if (current.contains("original")) {
                if (values.any { (key, value) -> current.getString(key, null) != value }) fail()
            } else {
                val edit = current.edit()
                values.forEach { (key, value) -> edit.putString(key, value) }
                if (!edit.commit()) fail()
            }
            if (!old.edit().clear().commit()) fail()
        }
        return current
    }

    private fun fail(): Nothing = throw GalaxyUsbVerificationFailure(GalaxyUsbVerificationFailure.Reason.RESTORE_REQUIRED)
}
