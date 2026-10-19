package com.shilapi.xcertplay.network

/** 只在 ADB 明确未执行命令时尝试宿主通道，未知结果交给恢复日志处理。 */
internal class WifiScanPauseTransport {
    var lastTransport = "none"
        private set

    fun switch(primary: () -> WifiScanSwitchResult, fallback: () -> WifiScanSwitchResult?): WifiScanSwitchResult {
        lastTransport = "adb"
        val result = primary()
        if (result !in setOf(WifiScanSwitchResult.ADB_OFF, WifiScanSwitchResult.ADB_NOT_APPROVED,
                WifiScanSwitchResult.ADB_PAIRING_ONLY)) return result
        val alternative = fallback() ?: return result
        lastTransport = "host-shell"
        return alternative
    }
}
