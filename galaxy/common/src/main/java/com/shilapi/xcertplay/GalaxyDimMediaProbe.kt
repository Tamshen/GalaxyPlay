package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** L6／L7 共用的原车仪表媒体入口检查；只读组件和权限，不绑定、不发送媒体。 */
internal object GalaxyDimMediaProbe {
    const val PACKAGE = "com.autolink.diminteraction"
    const val SERVICE = "$PACKAGE.DIMMediaInteractioncService"
    fun inspect(context: Context): Map<String, Long> {
        fun flag(value: Boolean) = if (value) 1L else 0L
        return try {
            val info = context.packageManager.getServiceInfo(ComponentName(PACKAGE, SERVICE), 0)
            mapOf("dimService" to 1L, "dimEnabled" to flag(info.enabled && info.applicationInfo?.enabled != false),
                "dimExported" to flag(info.exported), "dimPermissionDeclared" to flag(!info.permission.isNullOrEmpty()),
                "dimPermissionGranted" to (info.permission?.takeIf(String::isNotEmpty)?.let {
                    flag(context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED)
                } ?: -1L))
        } catch (error: Exception) {
            mapOf("dimService" to when (error) {
                is PackageManager.NameNotFoundException -> 2L
                is SecurityException -> 3L
                else -> 4L
            })
        }
    }
}
