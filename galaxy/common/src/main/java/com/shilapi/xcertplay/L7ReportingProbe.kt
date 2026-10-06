package com.shilapi.xcertplay

/** 原厂互联线索的专项只读检查；候选权限和本地 SDK 可见性不代表服务授权或业务成功。 */
internal class L7ReportingProbe(
    private val permission: (String) -> L7ProbeItem,
    private val sdkClass: (String) -> String,
) {
    fun inspect(id: String): L7ProbeItem {
        val spec = specs.getValue(id)
        val permissions = spec.permissions.map(permission)
        val classes = spec.classes.associateWith(sdkClass)
        val facts = linkedMapOf<String, String?>(
            "permissionRequirement" to "CANDIDATES_NOT_CONFIRMED_REQUIRED",
            "candidatePermissions" to spec.permissions.joinToString(","),
            "declaredCount" to permissions.count { it.facts["declared"] == "true" }.toString(),
            "grantedCount" to permissions.count { it.facts["granted"] == "true" }.toString(),
            "permissionCount" to permissions.size.toString(),
            "definitionVisibleCount" to permissions.count { it.facts["definitionVisible"] == "true" }.toString(),
            "restrictedCount" to permissions.count { it.facts["appOpMode"] in setOf("IGNORED", "ERRORED") }.toString(),
            "sdkVisibleCount" to classes.values.count { it == "VISIBLE" }.toString(),
            "sdkCount" to classes.size.toString(),
            "sdkChecks" to classes.entries.joinToString(";") { "${it.key}=${it.value}" },
            "effectiveCall" to "NOT_RUN",
            "serviceAuthorization" to "NOT_TESTED",
            "reportingSupported" to "UNKNOWN",
        )
        permissions.forEach { item ->
            facts["permission.${item.name}"] = permissionFields.joinToString(";") { "$it=${item.facts[it] ?: "unknown"}" } +
                ";reason=${item.reason}"
        }
        if (id == "REPORT-QNX") facts["qnxProtocol"] = "UNCONFIRMED_MEDIACENTER_DOWNSTREAM"
        return L7ProbeItem(id, id, "REPORTING", L7ProbeOutcome.UNKNOWN, "REPORTING_UNVERIFIED", facts)
    }

    companion object {
        private data class Spec(val permissions: List<String>, val classes: List<String>)
        private val mediaPermissions = listOf(
            "com.ecarx.media.provider.WRITE_DYNAMIC_SOURCE_DATA",
            "com.ecarx.media.provider.READ_SOURCE_LIST_DATA",
            "com.ecarx.media.provider.READ_DYNAMIC_SOURCE_DATA",
        )
        private val mediaClasses = listOf("com.ecarx.eas.sdk.mediacenter.MediaCenterAPI", "com.ecarx.eas.sdk.mediacenter.MusicClient")
        private val specs = linkedMapOf(
            "REPORT-MEDIA" to Spec(mediaPermissions, mediaClasses),
            "REPORT-HUD" to Spec(listOf("ecarx.openapi.permission.NAVI_SERVICE"), listOf(
                "com.ecarx.xui.adaptapi.diminteraction.NaviInteraction", "ecarx.fw.api.diminteraction.EcarxNaviInteraction")),
            "REPORT-QNX" to Spec(mediaPermissions, mediaClasses),
        )
        val ids = specs.keys.toList()
        val permissionFactKeys = specs.values.flatMap { it.permissions }.distinct().map { "permission.$it" }.toSet()
        private val permissionFields = listOf("declared", "granted", "definitionVisible", "definitionPackage",
            "protectionLevel", "appOp", "appOpMode")

        /** 禁止类初始化；缺类、依赖缺失或隐藏限制仅说明当前应用不可见。 */
        fun sdkVisibility(name: String, loader: ClassLoader): String = try {
            Class.forName(name, false, loader)
            "VISIBLE"
        } catch (_: ClassNotFoundException) { "NOT_VISIBLE" }
        catch (error: LinkageError) { "UNAVAILABLE:${error.javaClass.simpleName}" }
        catch (error: Exception) { "QUERY_FAILED:${error.javaClass.simpleName}" }
    }
}
