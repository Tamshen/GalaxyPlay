package com.shilapi.xcertplay

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.Settings

/** 专用访问查询只读取授权状态；不弹出授权页、不读取使用记录或外部文件。 */
internal class L7SpecialAccessProbe(private val context: Context) {
    fun inspect(name: String, granted: Boolean?): Map<String, String?> {
        val method = when (name) {
            Manifest.permission.WRITE_SETTINGS -> "Settings.System.canWrite"
            Manifest.permission.SYSTEM_ALERT_WINDOW -> "Settings.canDrawOverlays"
            Manifest.permission.PACKAGE_USAGE_STATS -> "AppOpsManager.OPSTR_GET_USAGE_STATS"
            Manifest.permission.REQUEST_INSTALL_PACKAGES -> "PackageManager.canRequestPackageInstalls"
            Manifest.permission.MANAGE_EXTERNAL_STORAGE -> "Environment.isExternalStorageManager"
            else -> return mapOf("specialAccess" to "NOT_APPLICABLE")
        }
        val facts = linkedMapOf<String, String?>("specialAccessMethod" to method, "specialAccess" to "UNKNOWN")
        if (name == Manifest.permission.MANAGE_EXTERNAL_STORAGE && Build.VERSION.SDK_INT < 30) {
            facts["specialAccess"] = "NOT_APPLICABLE"
            facts["specialAccessReason"] = "REQUIRES_API_30"
            return facts
        }
        try {
            val allowed = when (name) {
                Manifest.permission.WRITE_SETTINGS -> Settings.System.canWrite(context)
                Manifest.permission.SYSTEM_ALERT_WINDOW -> Settings.canDrawOverlays(context)
                Manifest.permission.REQUEST_INSTALL_PACKAGES -> context.packageManager.canRequestPackageInstalls()
                Manifest.permission.MANAGE_EXTERNAL_STORAGE ->
                    if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else null
                else -> usageAccess(granted, facts)
            }
            facts["specialAccess"] = when (allowed) { true -> "ALLOWED"; false -> "DENIED"; null -> "UNKNOWN" }
        } catch (error: Exception) {
            facts["specialAccessExceptionType"] = error.javaClass.simpleName
        }
        return facts
    }

    private fun usageAccess(granted: Boolean?, facts: MutableMap<String, String?>): Boolean? {
        val mode = context.getSystemService(AppOpsManager::class.java)
            .unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        facts["specialAccessAppOpMode"] = mode.toString()
        return when (mode) {
            AppOpsManager.MODE_ALLOWED -> true
            AppOpsManager.MODE_IGNORED, AppOpsManager.MODE_ERRORED -> false
            // 使用情况访问的默认模式回退到普通授权；其余模式不推定为允许。
            AppOpsManager.MODE_DEFAULT -> granted
            else -> null
        }
    }
}
