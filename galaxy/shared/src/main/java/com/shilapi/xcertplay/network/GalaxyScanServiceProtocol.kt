package com.shilapi.xcertplay.network

internal enum class GalaxyScanState { ENABLED, DISABLED, UNKNOWN, DENIED, UNSUPPORTED }
internal enum class GalaxyScanAction { INSPECT, DISABLE, ENABLE }

/** 只接收白名单状态；系统转储中的网络标识、请求方与扫描结果不离开助手。 */
internal object GalaxyScanServiceProtocol {
    const val HEADER = "GALAXY_SCAN_SERVICE_V1"

    fun parse(output: String?): GalaxyScanState {
        val line = output?.lineSequence()?.map(String::trim)?.lastOrNull { it.startsWith("$HEADER|") }
        return GalaxyScanState.entries.firstOrNull { line == "$HEADER|${it.name}" } ?: GalaxyScanState.UNKNOWN
    }

    fun command(apk: String, action: GalaxyScanAction): String {
        val quoted = "'" + apk.replace("'", "'\"'\"'") + "'"
        return "CLASSPATH=$quoted app_process /system/bin ${GalaxyScanServiceMain::class.java.name} ${action.name}"
    }

    fun scannerState(lines: Sequence<String>): GalaxyScanState {
        var section = ""
        val states = mutableMapOf<String, String>()
        var characters = 0
        for (raw in lines) {
            characters += raw.length
            if (characters > 1_048_576) return GalaxyScanState.UNKNOWN
            val line = raw.trim()
            if (line.endsWith("StateMachine:")) section = line.removeSuffix(":")
            if (line.startsWith("curState=") && section in setOf(SINGLE, PNO)) {
                if (states.put(section, line.removePrefix("curState=")) != null) return GalaxyScanState.UNKNOWN
            }
        }
        val single = states[SINGLE] ?: return GalaxyScanState.UNKNOWN
        val pno = states[PNO] ?: return GalaxyScanState.UNKNOWN
        return when {
            single == "DefaultState" && pno == "DefaultState" -> GalaxyScanState.DISABLED
            single in setOf("IdleState", "ScanningState", "DriverStartedState") &&
                pno in setOf("StartedState", "HwPnoScanState", "SingleScanState") -> GalaxyScanState.ENABLED
            else -> GalaxyScanState.UNKNOWN
        }
    }

    private const val SINGLE = "WifiSingleScanStateMachine"
    private const val PNO = "WifiPnoScanStateMachine"
}
