package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** 根据 SDK 样本的精确组件读取入口证据，不绑定服务或调用其 Binder。 */
internal object L7VendorServiceProbe {
    const val PACKAGE = "ecarx.xsf.mediacenter"
    const val SERVICE = "ecarx.xsf.mediacenter.MediaCenterService"
    val factKeys = setOf("mediaProviderPackage", "mediaProviderQuery", "mediaProviderExceptionType",
        "mediaProviderEnabled", "mediaProviderEasSupport", "mediaProviderEasSupportPresent", "mediaProviderEasSupportReason",
        "mediaServiceQuery", "mediaServiceExceptionType", "mediaServiceExported", "mediaServiceEnabled",
        "mediaServicePermission", "mediaServicePermissionDeclared", "mediaServicePermissionGranted", "mediaServiceAuthorization")

    fun inspect(context: Context): Map<String, String?> {
        val result = linkedMapOf<String, String?>("mediaProviderPackage" to PACKAGE,
            "mediaServiceAuthorization" to "UNTESTED_NO_BINDER_CALL")
        query("mediaProvider", result) {
            val info = context.packageManager.getApplicationInfo(PACKAGE, PackageManager.GET_META_DATA)
            result["mediaProviderEnabled"] = info.enabled.toString()
            val present = info.metaData?.containsKey("EAS_SUPPORT") == true
            result["mediaProviderEasSupportPresent"] = present.toString()
            // 缺失与 0 分开记录，不替 SDK 猜测实际选择的连接分支。
            val value = if (present) info.metaData?.get("EAS_SUPPORT") as? Int else null
            result["mediaProviderEasSupport"] = value?.toString()
            result["mediaProviderEasSupportReason"] = when {
                !present -> "ABSENT"
                value == null -> "VALUE_NOT_INTEGER"
                else -> "INTEGER_VALUE"
            }
        }
        query("mediaService", result) {
            val info = context.packageManager.getServiceInfo(ComponentName(PACKAGE, SERVICE), 0)
            result["mediaServiceExported"] = info.exported.toString()
            result["mediaServiceEnabled"] = info.enabled.toString()
            result["mediaServicePermissionDeclared"] = (!info.permission.isNullOrEmpty()).toString()
            result["mediaServicePermission"] = info.permission?.takeIf(String::isNotEmpty)
            result["mediaServicePermissionGranted"] = info.permission?.takeIf(String::isNotEmpty)?.let {
                (context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED).toString()
            }
        }
        return result
    }

    private fun query(key: String, result: MutableMap<String, String?>, action: () -> Unit) {
        try { action(); result["${key}Query"] = "VISIBLE" }
        catch (error: Exception) {
            result["${key}Query"] = when (error) {
                is PackageManager.NameNotFoundException -> "NOT_VISIBLE_OR_UNINSTALLED"
                is SecurityException -> "QUERY_DENIED"
                else -> "QUERY_FAILED"
            }
            result["${key}ExceptionType"] = error.javaClass.simpleName
        }
    }
}
