package com.shilapi.xcertplay.network

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** 仅在 P2P 会话已建立后接管扫描；与连接前的自动找网控制分别持有恢复责任。 */
internal class GalaxyConnectedScanGuard(
    private val request: (Context, GalaxyScanAction) -> GalaxyScanState = GalaxyScanServiceClient::request,
    private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "galaxy-connected-scan-guard").apply { isDaemon = true }
    },
) {
    private class Lease(val current: () -> Boolean, val log: (String) -> Unit) {
        val started = SystemClock.elapsedRealtime()
        @Volatile var listenerReady = false
        @Volatile var scans = 0L
        @Volatile var lastScan = 0L
    }
    private val owners = ConcurrentHashMap<Any, Lease>()
    private var task: ScheduledFuture<*>? = null
    private var session: GalaxyScanGuardSession? = null
    private var app: Context? = null
    @Volatile private var last = "stage=NOT_CONNECTED service=UNKNOWN"

    fun connected(context: Context, owner: Any, current: () -> Boolean, log: (String) -> Unit) {
        if (Build.VERSION.SDK_INT != 30 || !current()) return
        owners.putIfAbsent(owner, Lease(current, log))
        enqueue(context) {
            if (!current()) owners.remove(owner)
            if (owners.isNotEmpty()) it.start() else it.stop()
        }
    }

    fun observe(owner: Any, updated: Boolean) {
        val lease = owners[owner] ?: return
        lease.listenerReady = true
        if (updated) {
            lease.scans++
            lease.lastScan = SystemClock.elapsedRealtime()
        }
    }

    fun listening(owner: Any) { owners[owner]?.listenerReady = true }

    fun snapshot(owner: Any): String {
        val lease = owners[owner] ?: return "ScanGuard stage=NOT_CONNECTED"
        val now = SystemClock.elapsedRealtime()
        val quiet = now - maxOf(lease.started, lease.lastScan)
        val observation = when {
            !lease.listenerReady -> "LISTENER_UNAVAILABLE"
            quiet < 30_000 -> "OBSERVING"
            else -> "NO_UPDATED_SCAN_30S"
        }
        return "ScanGuard $last updatedScans=${lease.scans} quietMs=$quiet observation=$observation " +
            "observationScope=COMPLETION_BROADCAST_NOT_RF"
    }

    fun release(context: Context, owner: Any, log: (String) -> Unit) {
        owners.remove(owner)
        enqueue(context) {
            if (owners.isEmpty()) {
                val restored = it.stop()
                runCatching { log("ScanGuard stage=RELEASE restored=$restored $last") }
            }
        }
    }

    fun recover(context: Context) = enqueue(context) { it.recover() }

    private fun enqueue(context: Context, action: (GalaxyScanGuardSession) -> Unit) {
        if (Build.VERSION.SDK_INT != 30) return
        worker.execute {
            app = context.applicationContext
            runCatching { action(state(context.applicationContext)) }
                .onFailure { publish("stage=CONTROL_FAILED service=UNKNOWN failure=${it.javaClass.simpleName}") }
            schedule()
        }
    }

    private fun schedule() {
        if (task != null) return
        // 无会话且无需恢复时，不保留后台轮询。
        val context = app ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (owners.isEmpty() && !prefs.contains(BOOT)) return
        task = worker.schedule({
            task = null
            owners.entries.removeAll { !it.value.current() }
            runCatching {
                val state = state(context)
                if (owners.isEmpty()) state.stop() else {
                    state.check()
                    owners.forEach { (owner, lease) -> runCatching { lease.log(snapshot(owner)) } }
                }
            }.onFailure { publish("stage=CONTROL_FAILED service=UNKNOWN failure=${it.javaClass.simpleName}") }
            schedule()
        }, 30, TimeUnit.SECONDS)
    }

    private fun state(context: Context): GalaxyScanGuardSession {
        session?.let { return it }
        val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        check(boot >= 0)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // 系统重启会重新初始化扫描服务，旧启动周期的恢复责任不能覆盖新状态。
        if (prefs.contains(BOOT) && prefs.getInt(BOOT, -1) != boot)
            check(prefs.edit().remove(BOOT).commit())
        var pending = prefs.contains(BOOT)
        return GalaxyScanGuardSession(
            request = { action -> request(context, action) },
            pending = { pending },
            savePending = { value ->
                val edit = prefs.edit()
                if (value) edit.putInt(BOOT, boot) else edit.remove(BOOT)
                edit.commit().also { if (it) pending = value }
            },
            report = ::publish,
        ).also { session = it }
    }

    private fun publish(message: String) {
        last = "$message monoMs=${SystemClock.elapsedRealtime()}"
        owners.values.forEach { lease -> runCatching { lease.log("ScanGuard $last") } }
    }

    companion object {
        private const val PREFS = "galaxy_connected_scan_guard"
        private const val BOOT = "restore_enabled_boot"
    }
}
