package com.shilapi.xcertplay.network

import android.os.Build
import android.content.Context
import java.util.concurrent.TimeUnit

/** 与 P2P 广播配置共用已有本机维护通道；不启用服务或修改 ADB 设置。 */
object GalaxyWifiScanPause : WifiScanPauseControl {
    private val autojoin = ProcessWifiScanPause(fallback = { _, command -> GalaxyWifiScanShell.run(command) })
    private val connected = GalaxyConnectedScanGuard()

    override fun acquire(app: Context, owner: Any, current: () -> Boolean, log: (String) -> Unit) =
        autojoin.acquire(app, owner, current, log)

    override fun recover(app: Context) { autojoin.recover(app); connected.recover(app) }

    override fun release(app: Context, owner: Any, log: (String) -> Unit) {
        connected.release(app, owner, log)
        autojoin.release(app, owner, log)
    }

    override fun sessionActive(app: Context, owner: Any, current: () -> Boolean, log: (String) -> Unit) =
        connected.connected(app, owner, current, log)

    override fun sessionEnded(app: Context, owner: Any, log: (String) -> Unit) = connected.release(app, owner, log)

    override fun scanObservation(owner: Any, updated: Boolean?) {
        if (updated == null) connected.listening(owner) else connected.observe(owner, updated)
    }

    override fun diagnosticSnapshot(owner: Any) = connected.snapshot(owner)
}

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
