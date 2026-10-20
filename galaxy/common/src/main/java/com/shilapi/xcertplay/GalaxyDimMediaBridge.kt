package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.net.Uri
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import java.io.Closeable
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** 自动连接原车 DIM；只发布当前会话且拥有媒体焦点的最新状态。 */
internal class GalaxyDimMediaBridge(
    private val context: Context,
    private val source: Int,
    private val current: () -> Boolean,
    private val focus: () -> String?,
    private val report: (String) -> Unit = L7DebugLog::record,
    private val probe: () -> Map<String, Long> = { GalaxyDimMediaProbe.inspect(context) },
    private val load: (IBinder) -> GalaxyDimMediaOutput = { GalaxyDimMediaContract.load(context, it) },
    private val reconnectDelayMillis: Long = 5000,
) : Closeable {
    internal data class Snapshot(val value: CarPlayNowPlaying, val artwork: Uri?, val track: String)
    @Volatile private var closed = false
    @Volatile private var latest: Snapshot? = null
    @Volatile var status = 0L
        private set
    private val worker = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "galaxy-dim-media").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    private val covers = GalaxyDimArtwork(context)
    private var connection: ServiceConnection? = null
    private var binder: IBinder? = null
    private var contract: GalaxyDimMediaOutput? = null
    private var attempts = 0
    private var failures = 0
    private var sent: Snapshot? = null
    private var pending: ScheduledFuture<*>? = null
    private var retry: ScheduledFuture<*>? = null
    private var lastEvent: String? = null

    init { submit { connect() } }

    fun update(value: CarPlayNowPlaying, artwork: Uri?, track: String) {
        latest = Snapshot(value, artwork, track)
        wake()
    }

    @Synchronized fun wake() {
        if (closed || pending != null) return
        pending = worker.schedule({
            synchronized(this) { pending = null }
            if (!closed && current()) publish()
        }, 100, TimeUnit.MILLISECONDS)
    }

    private fun submit(action: () -> Unit) {
        synchronized(this) {
            if (!closed) worker.execute { if (!closed && current()) action() }
        }
    }

    private fun connect() {
        if (closed || !current() || connection != null) return
        if (attempts >= 3) { event("BIND_RETRY_LIMIT", -1); return }
        val probe = probe()
        if (probe["dimService"] != 1L || probe["dimEnabled"] != 1L || probe["dimExported"] != 1L ||
            probe["dimPermissionGranted"] == 0L) { event("UNAVAILABLE", -1); return }
        attempts++
        val next = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) = submit {
                if (connection !== this) return@submit
                try {
                    contract = load(service)
                    binder = service
                    sent = null
                    failures = 0
                    retry?.cancel(false)
                    event("CONNECTED", 1)
                    wake()
                } catch (_: Exception) {
                    event("INCOMPATIBLE", -1)
                    disconnect()
                }
            }
            override fun onServiceDisconnected(name: ComponentName) = lost(this)
            override fun onBindingDied(name: ComponentName) = lost(this)
            override fun onNullBinding(name: ComponentName) = lost(this)
        }
        connection = next
        try {
            if (!context.bindService(Intent().setComponent(ComponentName(GalaxyDimMediaProbe.PACKAGE,
                    GalaxyDimMediaProbe.SERVICE)), next, Context.BIND_AUTO_CREATE)) {
                disconnect()
                event("BIND_REJECTED", -1)
            }
        } catch (_: Exception) { disconnect(); event("BIND_FAILED", -1) }
        // 包括 bind 返回 true 但永远没有回调的情况，五秒后有限重试。
        retry = worker.schedule({
            if (!closed && current() && binder == null) { disconnect(); connect() }
        }, reconnectDelayMillis, TimeUnit.MILLISECONDS)
    }

    private fun lost(owner: ServiceConnection) = submit {
        if (connection !== owner) return@submit
        disconnect()
        event("DISCONNECTED", 0)
        retry?.cancel(false)
        retry = worker.schedule({ if (!closed && current()) connect() }, reconnectDelayMillis, TimeUnit.MILLISECONDS)
    }

    private fun publish() {
        val next = latest ?: return
        if (!next.value.playbackKnown || next.value.title.isNullOrBlank()) return
        try {
            if (focus() != context.packageName) { sent = null; event("WAITING_FOCUS", 0); return }
            if (next == sent || failures >= 3) return
            val output = contract ?: return
            if (binder?.isBinderAlive != true) { connection?.let(::lost); return }
            val artwork = covers.prepare(next.artwork)
            if (closed || !current() || latest !== next || focus() != context.packageName) return
            output.send(next.value, artwork, next.track, source)
            if (!closed && current()) {
                sent = next
                failures = 0
                event("SENT_TO_FACTORY_SERVICE", 2)
            }
        } catch (error: Exception) {
            failures++
            event("SEND_FAILED_${error.javaClass.simpleName}", -1)
            if (failures < 3 && !closed) worker.schedule({ if (!closed && current()) publish() }, 1, TimeUnit.SECONDS)
        }
    }

    private fun disconnect() {
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
        binder = null
        contract = null
        sent = null
    }

    private fun event(value: String, state: Long) {
        status = state
        if (lastEvent == value) return
        lastEvent = value
        runCatching { report("MediaCenter: dimPublish=$value source=$source attempts=$attempts") }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        latest = null
        pending?.cancel(false)
        retry?.cancel(false)
        worker.queue.clear()
        worker.execute { disconnect(); covers.close() }
        worker.shutdown()
    }
}
