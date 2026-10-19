package com.shilapi.xcertplay.network

import android.os.Build
import java.util.concurrent.TimeUnit

/** 与 P2P 广播配置共用已有本机维护通道；不启用服务或修改 ADB 设置。 */
object GalaxyWifiScanPause : WifiScanPauseControl by ProcessWifiScanPause(
    fallback = { _, command -> GalaxyWifiScanShell.run(command) },
)

internal object GalaxyWifiScanShell {
    fun run(command: String, api: Int = Build.VERSION.SDK_INT,
            connect: (Long) -> GalaxyP2pAutoJoin.Shell = { deadline ->
                GalaxyP2pLoopback.open(deadline, { false })
            }): WifiScanSwitchResult? {
        if (api != 30) return null
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WifiScanPause.HELPER_TIMEOUT_MS.toLong())
        // 连接失败尚未运行助手，可保留 ADB 的原始失败；发送后的异常必须保留待恢复标记。
        val shell = try { connect(deadline) } catch (_: Exception) { return null }
        return try {
            shell.use { WifiScanPause.parse(it.run(command)) }
        } catch (_: Exception) {
            WifiScanSwitchResult.UNKNOWN
        }
    }
}
