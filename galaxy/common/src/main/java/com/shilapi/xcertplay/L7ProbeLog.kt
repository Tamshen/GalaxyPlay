package com.shilapi.xcertplay

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 手动采集生成有界脱敏日志；独立文件避免与正在写入的连接日志争用及轮转冲突。 */
internal object L7ProbeLog {
    const val MAX_BYTES = 512 * 1024
    val files = listOf("probe-previous.log", "probe-latest.log")
    private val factKeys = setOf(
        "manufacturer", "model", "android", "api", "abi", "securityPatch", "appVersion", "versionCode",
        "uid", "pid", "userHandle", "minSdk", "targetSdk", "systemApp", "privilegedApp", "privilegedAppReason", "debuggable",
        "resourcePixels", "systemDensityDpi", "uiDensityDpi", "windowPixels", "displayId", "projectionBuffer", "sessionActive",
        "visibleSharedLibraries", "executorContext", "selinuxContext", "selinuxEnforcing", "shellSession", "rootSession", "hookSession",
        "visibleDeviceCount", "deviceTypes", "activeNetworkVisible", "wifi", "vpn", "usbHostFeature", "visibleDevices", "allowed",
        "declared", "definitionVisible", "definitionPackage", "protectionLevel", "granted", "appOp", "appOpMode", "effectiveCall",
        "protectionBase", "protectionFlags", "protectionFlagsRaw", "appOpModeRaw", "minimumApi", "staticWarnings",
        "definitionReason", "definitionExceptionType", "grantExceptionType", "appOpMappingExceptionType", "appOpExceptionType",
        "specialAccess", "specialAccessMethod", "specialAccessReason", "specialAccessExceptionType", "specialAccessAppOpMode",
        "videoPreference", "fpsPreference", "displayScalePreference", "connectionPreference", "authenticationBackend",
        "bluetoothExclusivePreference", "bluetoothGuardState", "audioMode", "musicActive", "sessionPresent",
        "permissionRequirement", "candidatePermissions", "declaredCount", "grantedCount", "permissionCount",
        "definitionVisibleCount", "restrictedCount", "sdkVisibleCount", "sdkCount", "sdkChecks",
        "activeSessionCount", "ownSessionCount", "ownPlaybackStates", "mediaSdkSource", "navigationSdkSource",
        "mediaContract", "navigationContract", "mediaExceptionType", "navigationExceptionType",
        "mediaCallbackKind", "mediaSourceEvidence", "mediaRegistration", "navigationMapping",
        "method", "apState", "hotspotEnabled", "ssidPresent", "passwordPresent", "security",
        "configValid", "passwordMasked", "exceptionType", "serviceAuthorization", "reportingSupported", "qnxProtocol",
    ) + L7ReportingProbe.permissionFactKeys + L7VendorServiceProbe.factKeys

    fun batch(report: L7ProbeReport) = report.id.replace("-", "").take(12)

    internal fun lines(report: L7ProbeReport): List<String> {
        val prefix = "L7_PROBE batch=${batch(report)}"
        val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        fun line(at: Long, body: String) = RemoteLogReport.redact("${clock.format(Date(at))}  $prefix $body")
        val output = mutableListOf<String>()
        val details = mutableListOf<String>()
        line(report.started, "event=begin startedAt=${report.started} version=${report.version} expected=${report.expected}")?.let(output::add)
        report.items.forEachIndexed { index, item ->
            // 权限名属于技术证据；异常格式被拒绝时仍保留序号与结果。
            val entry = item.id.takeIf { RemoteLogReport.redact("entry=$it ") != null } ?: "ENTRY_$index"
            val head = "item=$index entry=$entry status=${L7ProbeStatus.of(item)} result=${item.result} reason=${item.reason} at=${item.time}"
            line(item.time, head)?.let(output::add)
            val detailHead = "item=$index entry=$entry detail"
            var part = detailHead
            item.facts.filter { (key, value) -> key in factKeys && value != null }.forEach { (key, value) ->
                val safe = value!!.take(1200).replace(Regex("[\\r\\n\\t]"), " ")
                for ((chunk, text) in safe.chunked(180).withIndex()) {
                    val fact = "$key${if (safe.length > 180) "[$chunk]" else ""}=$text"
                    if (RemoteLogReport.redact(fact) == null) continue
                    if (part.length + fact.length > 580) {
                        line(item.time, part)?.let(details::add)
                        part = detailHead
                    }
                    part += " $fact"
                }
            }
            if (part != detailHead) line(item.time, part)?.let(details::add)
        }
        // 先保留每项结论，再填充长证据；细节预算不足不能吞掉后面的权限结果。
        output += details
        val end = line(report.finished ?: report.started,
            "event=end phase=${report.phase} collected=${report.items.count { it.reason != "NOT_RUN" }} expected=${report.expected}")!!
        var bytes = end.toByteArray(Charsets.UTF_8).size + 120
        val retained = output.takeWhile { candidate ->
            bytes += candidate.toByteArray(Charsets.UTF_8).size + 1
            bytes <= MAX_BYTES
        }.toMutableList()
        if (retained.size < output.size) retained += "$prefix event=truncated omittedLines=${output.size - retained.size}"
        retained += end
        return retained
    }

    /** 调用方已经在采集工作线程；先原子落盘，再发布到当前日志，失败由页面提示。 */
    @Synchronized fun write(context: Context, report: L7ProbeReport) {
        val evidence = report.configuration?.let { runCatching { GalaxyConfigurationEvidence.read(it) }.getOrNull() }
        val entries = listOfNotNull(evidence?.header) + lines(report).map { line ->
            (evidence?.let { "config_ref=${it.id} " } ?: "") + line
        }
        val folder = File(context.filesDir, "logs").apply { mkdirs() }
        val latest = File(folder, files.last())
        if (latest.isFile) save(File(folder, files.first()), latest.readBytes().take(MAX_BYTES).toByteArray())
        save(latest, entries.joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8))
        L7DebugLog.initialize(context)
        lines(report).forEach { L7DebugLog.recordConfiguration(it, evidence) }
    }

    @Synchronized fun read(context: Context): List<String> = files.flatMap { name ->
        val file = File(context.filesDir, "logs/$name")
        if (!file.isFile || file.length() > MAX_BYTES) emptyList()
        else runCatching { AtomicFile(file).openRead().bufferedReader().use { it.readLines() } }.getOrDefault(emptyList())
    }

    @Synchronized fun clear(context: Context) {
        files.forEach { name ->
            val file = File(context.filesDir, "logs/$name")
            AtomicFile(file).delete()
            check(!file.exists())
        }
    }

    private fun save(file: File, bytes: ByteArray) {
        require(bytes.size <= MAX_BYTES)
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) }
        catch (error: Exception) { atomic.failWrite(stream); throw error }
    }
}
