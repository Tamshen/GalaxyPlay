package com.shilapi.xcertplay

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject

/** 候选名称保留原样，仅查询；定义、请求和组件保护关系不混作权限声明。 */
internal class L7PermissionProbe(
    private val context: Context,
    private val specialAccess: (String, Boolean?) -> Map<String, String?> = L7SpecialAccessProbe(context)::inspect,
) {
    private val pm = context.packageManager
    private val own = pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
    private val declared = own.requestedPermissions.orEmpty().toSet()
    private val catalog = JSONObject(context.assets.open("l7-permission-catalog.json").bufferedReader().use { it.readText() })
    private val candidates = catalog.getJSONArray("permissions").let { array ->
        (0 until array.length()).map { array.getJSONObject(it) }.associateBy { it.getString("name") }
    }
    val names = (candidates.keys + declared).sorted()
    private val sources = catalog.getJSONArray("sources").let { array ->
        (0 until array.length()).map { array.getJSONObject(it) }.associateBy { it.getString("id") }
    }

    fun inspect(name: String): L7ProbeItem {
        val facts = linkedMapOf<String, String?>("rawName" to name, "declared" to (name in declared).toString(),
            "definitionVisible" to null, "definitionPackage" to null, "protectionLevel" to null,
            "protectionBase" to null, "protectionFlags" to null, "protectionFlagsRaw" to null,
            "granted" to null, "appOp" to null, "appOpMode" to null, "effectiveCall" to "NOT_RUN")
        reference(name, facts)
        val definitionVisible = definition(name, facts)
        val granted = query(facts, "grantExceptionType") { context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED }
        facts["granted"] = granted?.toString()
        val op = query(facts, "appOpMappingExceptionType") { AppOpsManager.permissionToOp(name) }
        facts["appOp"] = op
        val mode = if (op == null) null else query(facts, "appOpExceptionType") {
            context.getSystemService(AppOpsManager::class.java).unsafeCheckOpNoThrow(op, Process.myUid(), context.packageName)
        }
        facts["appOpModeRaw"] = mode?.toString()
        facts["appOpMode"] = when (mode) {
            AppOpsManager.MODE_ALLOWED -> "ALLOWED"
            AppOpsManager.MODE_IGNORED -> "IGNORED"
            AppOpsManager.MODE_ERRORED -> "ERRORED"
            AppOpsManager.MODE_DEFAULT -> "DEFAULT"
            AppOpsManager.MODE_FOREGROUND -> "FOREGROUND"
            else -> if (op == null) "NOT_APPLICABLE" else "UNKNOWN"
        }
        facts.putAll(specialAccess(name, granted))
        val minimum = minimumApi[name]
        facts["minimumApi"] = minimum?.toString()
        val (outcome, reason) = when {
            facts["specialAccess"] == "ALLOWED" -> L7ProbeOutcome.OBSERVED to "SPECIAL_ACCESS_ALLOWED"
            !definitionVisible && minimum != null && Build.VERSION.SDK_INT < minimum &&
                facts["definitionReason"] == "NOT_VISIBLE_OR_UNDEFINED" -> L7ProbeOutcome.NOT_APPLICABLE to "API_NOT_APPLICABLE"
            facts["specialAccess"] == "UNKNOWN" -> L7ProbeOutcome.UNKNOWN to
                if (facts["specialAccessExceptionType"] != null) "QUERY_FAILED" else "SPECIAL_ACCESS_UNKNOWN"
            !definitionVisible && facts["definitionReason"] != "NOT_VISIBLE_OR_UNDEFINED" -> L7ProbeOutcome.UNKNOWN to "QUERY_FAILED"
            !definitionVisible -> L7ProbeOutcome.UNKNOWN to "DEFINITION_NOT_VISIBLE"
            // 未声明只说明当前 APK 状态，不能预判后续补声明后的系统授权。
            name !in declared -> L7ProbeOutcome.UNKNOWN to "NOT_DECLARED"
            facts["specialAccess"] == "DENIED" -> L7ProbeOutcome.DENIED to "SPECIAL_ACCESS_DENIED"
            granted == null -> L7ProbeOutcome.UNKNOWN to "QUERY_FAILED"
            !granted -> L7ProbeOutcome.DENIED to "NOT_GRANTED"
            mode in setOf(AppOpsManager.MODE_IGNORED, AppOpsManager.MODE_ERRORED) -> L7ProbeOutcome.DENIED to "APP_OP_RESTRICTED"
            facts["appOpExceptionType"] != null || facts["appOpMappingExceptionType"] != null -> L7ProbeOutcome.UNKNOWN to "QUERY_FAILED"
            else -> L7ProbeOutcome.OBSERVED to "GRANTED_NOT_CALLED"
        }
        return L7ProbeItem("PERM:$name", name, "PERMISSION", outcome, reason, facts)
    }

    private fun reference(name: String, facts: MutableMap<String, String?>) {
        candidates[name]?.let { candidate ->
            facts["staticWarnings"] = candidate.getJSONArray("warnings").strings().joinToString(",")
            for (relation in listOf("requests", "definitions", "accessProtections")) {
                facts["reference.$relation"] = candidate.getJSONArray(relation).strings().joinToString(",")
            }
            val ids = listOf("requests", "definitions", "accessProtections").flatMap { candidate.getJSONArray(it).strings() }.toSet()
            facts["sourceManifestSha256"] = sources.values
                .filter { it.getString("id") in ids }.joinToString(";") { "${it.getString("id")}:${it.getString("manifestSha256")}" }
        }
    }

    private fun definition(name: String, facts: MutableMap<String, String?>): Boolean {
        try {
            val info = pm.getPermissionInfo(name, 0)
            facts["definitionVisible"] = "true"
            facts["definitionPackage"] = info.packageName
            facts["protectionLevel"] = info.protectionLevel.toString()
            facts["protectionBase"] = protectionBase(info.protectionLevel)
            val flags = info.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE.inv()
            facts["protectionFlagsRaw"] = flags.toString()
            facts["protectionFlags"] = protectionFlags(flags)
            return true
        } catch (_: PackageManager.NameNotFoundException) {
            facts["definitionReason"] = "NOT_VISIBLE_OR_UNDEFINED"
            facts["definitionExceptionType"] = "NameNotFoundException"
        } catch (error: Exception) {
            facts["definitionReason"] = error.javaClass.simpleName
            facts["definitionExceptionType"] = error.javaClass.simpleName
        }
        return false
    }

    private fun JSONArray.strings() = (0 until length()).map { getString(it) }

    private fun <T> query(facts: MutableMap<String, String?>, failure: String, call: () -> T): T? =
        try { call() } catch (error: Exception) { facts[failure] = error.javaClass.simpleName; null }

    private fun protectionBase(raw: Int) = when (raw and PermissionInfo.PROTECTION_MASK_BASE) {
        0 -> "NORMAL"; 1 -> "DANGEROUS"; 2 -> "SIGNATURE"; 3 -> "SIGNATURE_OR_SYSTEM"; 4 -> "INTERNAL"
        else -> "UNKNOWN"
    }

    private fun protectionFlags(raw: Int): String {
        val known = flags.keys.fold(0) { mask, flag -> mask or flag }
        val values = flags.filterKeys { raw and it != 0 }.values.toMutableList()
        if (raw and known.inv() != 0) values += "UNKNOWN_BITS:0x${(raw and known.inv()).toUInt().toString(16)}"
        return values.joinToString("|").ifEmpty { "NONE" }
    }

    companion object {
        private val flags = linkedMapOf(
            PermissionInfo.PROTECTION_FLAG_PRIVILEGED to "PRIVILEGED",
            PermissionInfo.PROTECTION_FLAG_DEVELOPMENT to "DEVELOPMENT",
            PermissionInfo.PROTECTION_FLAG_APPOP to "APPOP",
            PermissionInfo.PROTECTION_FLAG_PRE23 to "PRE23",
            PermissionInfo.PROTECTION_FLAG_INSTALLER to "INSTALLER",
            PermissionInfo.PROTECTION_FLAG_VERIFIER to "VERIFIER",
            PermissionInfo.PROTECTION_FLAG_PREINSTALLED to "PREINSTALLED",
            PermissionInfo.PROTECTION_FLAG_SETUP to "SETUP",
            PermissionInfo.PROTECTION_FLAG_INSTANT to "INSTANT",
            PermissionInfo.PROTECTION_FLAG_RUNTIME_ONLY to "RUNTIME_ONLY",
        )
        private val minimumApi = mapOf(
            "android.permission.MANAGE_EXTERNAL_STORAGE" to 30,
            "android.permission.BLUETOOTH_CONNECT" to 31, "android.permission.BLUETOOTH_SCAN" to 31,
            "android.permission.MANAGE_ONGOING_CALLS" to 31,
            "android.permission.NEARBY_WIFI_DEVICES" to 33, "android.permission.POST_NOTIFICATIONS" to 33,
            "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" to 34,
            "android.permission.FOREGROUND_SERVICE_MICROPHONE" to 34,
            "android.permission.FOREGROUND_SERVICE_LOCATION" to 34,
            "android.permission.FOREGROUND_SERVICE_SPECIAL_USE" to 34,
        )
    }
}
