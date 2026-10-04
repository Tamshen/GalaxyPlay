package com.shilapi.xcertplay

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject

/** 候选名称保留原样，仅查询；定义、请求和组件保护关系不混作权限声明。 */
internal class L7PermissionProbe(private val context: Context) {
    private val pm = context.packageManager
    private val own = pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
    private val declared = own.requestedPermissions.orEmpty().toSet()
    private val catalog = JSONObject(context.assets.open("l7-permission-catalog.json").bufferedReader().use { it.readText() })
    private val candidates = catalog.getJSONArray("permissions").let { array ->
        (0 until array.length()).map { array.getJSONObject(it) }.associateBy { it.getString("name") }
    }
    val names = (candidates.keys + declared).sorted()

    fun inspect(name: String): L7ProbeItem {
        val facts = linkedMapOf<String, String?>("rawName" to name, "declared" to (name in declared).toString(),
            "definitionVisible" to null, "definitionPackage" to null, "protectionLevel" to null,
            "granted" to null, "appOp" to null, "appOpMode" to null, "effectiveCall" to "NOT_RUN")
        candidates[name]?.let { candidate ->
            facts["staticWarnings"] = candidate.getJSONArray("warnings").strings().joinToString(",")
            for (relation in listOf("requests", "definitions", "accessProtections")) {
                facts["reference.$relation"] = candidate.getJSONArray(relation).strings().joinToString(",")
            }
            val sources = catalog.getJSONArray("sources")
            val ids = listOf("requests", "definitions", "accessProtections").flatMap { candidate.getJSONArray(it).strings() }.toSet()
            facts["sourceManifestSha256"] = (0 until sources.length()).map { sources.getJSONObject(it) }
                .filter { it.getString("id") in ids }.joinToString(";") { "${it.getString("id")}:${it.getString("manifestSha256")}" }
        }
        var definitionVisible = false
        try {
            val info = pm.getPermissionInfo(name, 0)
            definitionVisible = true
            facts["definitionVisible"] = "true"
            facts["definitionPackage"] = info.packageName
            facts["protectionLevel"] = info.protectionLevel.toString()
        } catch (_: PackageManager.NameNotFoundException) {
            facts["definitionReason"] = "NOT_VISIBLE_OR_UNDEFINED"
        } catch (error: Exception) { facts["definitionReason"] = error.javaClass.simpleName }
        val granted = runCatching { context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED }.getOrNull()
        facts["granted"] = granted?.toString()
        val op = runCatching { AppOpsManager.permissionToOp(name) }.getOrNull()
        facts["appOp"] = op
        val mode = if (op == null) null else runCatching {
            context.getSystemService(AppOpsManager::class.java).unsafeCheckOpNoThrow(op, Process.myUid(), context.packageName)
        }.getOrNull()
        facts["appOpMode"] = when (mode) {
            AppOpsManager.MODE_ALLOWED -> "ALLOWED"
            AppOpsManager.MODE_IGNORED -> "IGNORED"
            AppOpsManager.MODE_ERRORED -> "ERRORED"
            AppOpsManager.MODE_DEFAULT -> "DEFAULT"
            AppOpsManager.MODE_FOREGROUND -> "FOREGROUND"
            else -> if (op == null) "NOT_APPLICABLE" else "UNKNOWN"
        }
        val (outcome, reason) = when {
            !definitionVisible -> L7ProbeOutcome.UNKNOWN to "DEFINITION_NOT_VISIBLE"
            name !in declared -> L7ProbeOutcome.DENIED to "NOT_DECLARED"
            granted == null -> L7ProbeOutcome.UNKNOWN to "QUERY_FAILED"
            !granted -> L7ProbeOutcome.DENIED to "NOT_GRANTED"
            mode in setOf(AppOpsManager.MODE_IGNORED, AppOpsManager.MODE_ERRORED) -> L7ProbeOutcome.DENIED to "APP_OP_RESTRICTED"
            else -> L7ProbeOutcome.OBSERVED to "GRANTED_NOT_CALLED"
        }
        return L7ProbeItem("PERM:$name", name, "PERMISSION", outcome, reason, facts)
    }

    private fun JSONArray.strings() = (0 until length()).map { getString(it) }
}
