package com.shilapi.xcertplay.network

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import java.io.Closeable

/** 沿用 xcertplay 无线性能锁；控制器关闭后拒绝迟到的会话回调重新持锁。 */
internal class WirelessPerformanceLock(
    context: Context,
    private val log: (String) -> Unit,
) : Closeable {
    private val app = context.applicationContext
    private var highPerformance: WifiManager.WifiLock? = null
    private var lowLatency: WifiManager.WifiLock? = null
    private var closed = false

    @Synchronized fun diagnosticSnapshot(): String =
        "wifiLockHighHeld=${runCatching { highPerformance?.isHeld == true }.getOrDefault(false)} " +
            "wifiLockLowHeld=${runCatching { lowLatency?.isHeld == true }.getOrDefault(false)} " +
            "wifiLockClosed=$closed wifiLockEvidence=request_only"

    @Synchronized fun acquire() {
        if (closed) return
        val manager = app.getSystemService(WifiManager::class.java) ?: return
        if (highPerformance == null) highPerformance = acquire(manager,
            WifiManager.WIFI_MODE_FULL_HIGH_PERF, "GalaxyPlay:high-performance")
        if (Build.VERSION.SDK_INT >= 29 && lowLatency == null) lowLatency = acquire(manager,
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "GalaxyPlay:low-latency")
    }

    private fun acquire(manager: WifiManager, mode: Int, tag: String): WifiManager.WifiLock? = try {
        manager.createWifiLock(mode, tag).apply {
            setReferenceCounted(false)
            acquire()
        }.also { trace("Wireless Wi-Fi lock acquired mode=$mode") }
    } catch (error: RuntimeException) {
        trace("Wireless Wi-Fi lock unavailable mode=$mode exceptionType=${error.javaClass.simpleName}")
        null
    }

    /** 会话结束可释放后再次使用；最终关闭则永久拒绝重新获取。 */
    @Synchronized fun release() {
        listOfNotNull(lowLatency, highPerformance).forEach { lock ->
            runCatching { if (lock.isHeld) lock.release() }.onFailure {
                trace("Wireless Wi-Fi lock release failed exceptionType=${it.javaClass.simpleName}")
            }
        }
        lowLatency = null
        highPerformance = null
    }

    @Synchronized override fun close() { closed = true; release() }
    private fun trace(message: String) { runCatching { log(message) } }
}
