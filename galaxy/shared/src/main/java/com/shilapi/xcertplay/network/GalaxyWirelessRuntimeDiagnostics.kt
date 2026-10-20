package com.shilapi.xcertplay.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import java.io.Closeable

/** 仅监听系统通知，不主动扫描；扫描结果通知不能冒充扫描开始或射频离信道证据。 */
internal class GalaxyWirelessRuntimeDiagnostics(
    context: Context,
    private val log: (String) -> Unit,
    private val clockMs: () -> Long = SystemClock::elapsedRealtime,
    private val station: () -> String = { stationSnapshot(context.applicationContext) },
    private val onScanObservation: (Boolean?) -> Unit = {},
) : Closeable {
    private val app = context.applicationContext
    private var closed = false
    private var registered = false
    private var attempted = false
    private var registration = "not_started"
    private var scans = 0L
    private var updatedScans = 0L
    private var lastScan: Long? = null
    private var lastUpdated: Long? = null
    private var lastReported: Long? = null
    private var suppressed = 0L
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) return
            scanResults(intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false))
        }
    }

    @Synchronized fun start() {
        if (closed || attempted) return
        attempted = true
        try {
            val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else app.registerReceiver(receiver, filter)
            registered = true
            registration = "registered"
        } catch (error: RuntimeException) {
            registration = "unavailable_${error.javaClass.simpleName}"
        }
        event("stage=LISTENER monoMs=${clockMs()} scanBroadcast=$registration evidence=completion_only")
    }

    @Synchronized private fun scanResults(updated: Boolean) {
        if (closed || !registered) return
        runCatching { onScanObservation(updated) }
        val now = clockMs()
        scans++
        lastScan = now
        if (updated) { updatedScans++; lastUpdated = now }
        // 异常广播突发只计数；保留最近通知时间，避免诊断本身挤占音频处理。
        if (lastReported?.let { now >= it && now - it < 1000 } == true) { suppressed++; return }
        event("stage=SCAN_RESULTS monoMs=$now updated=$updated scanEvents=$scans " +
            "updatedEvents=$updatedScans suppressed=$suppressed evidence=completion_only timestampScope=callback")
        lastReported = now
        suppressed = 0
    }

    @Synchronized fun snapshot(): String {
        if (registered && !closed) runCatching { onScanObservation(null) }
        val now = clockMs()
        fun age(at: Long?) = at?.let { (now - it).coerceAtLeast(0).toString() } ?: "unknown"
        val state = if (closed) "stationQuery=closed" else try { station() }
            catch (error: RuntimeException) { "stationQuery=unavailable failureClass=${error.javaClass.simpleName}" }
        return "wirelessRuntime monoMs=$now scanBroadcast=$registration scanEvents=$scans updatedEvents=$updatedScans " +
            "lastScanAgoMs=${age(lastScan)} lastUpdatedScanAgoMs=${age(lastUpdated)} " +
            "scanEvidence=completion_only scanStart=not_observed $state"
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        if (registered) runCatching { app.unregisterReceiver(receiver) }
        registered = false
        event("stage=STOP monoMs=${clockMs()} scanEvents=$scans updatedEvents=$updatedScans suppressed=$suppressed")
    }

    private fun event(value: String) { runCatching { log("WirelessRuntime $value") } }

    companion object {
        @Suppress("DEPRECATION")
        private fun stationSnapshot(context: Context): String {
            val wifi = context.getSystemService(WifiManager::class.java) ?: return "stationQuery=no_service"
            val info = wifi.connectionInfo
            // 以下指标仅属于普通 Wi-Fi 客户端，不能当作 P2P 对端的信号或速率。
            return "stationMetricsScope=STA wifiState=${wifi.wifiState} " +
                "stationState=${info?.supplicantState?.name ?: "unknown"} " +
                "stationMHz=${info?.frequency?.takeIf { it > 0 } ?: "unknown"} " +
                "stationRssiDbm=${info?.rssi?.takeIf { it in -126..-1 } ?: "unknown"} " +
                "stationLinkMbps=${info?.linkSpeed?.takeIf { it > 0 } ?: "unknown"}"
        }
    }
}
