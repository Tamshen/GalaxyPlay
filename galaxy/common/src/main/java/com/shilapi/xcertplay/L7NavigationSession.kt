package com.shilapi.xcertplay

import android.os.SystemClock
import com.shilapi.xcertplay.hud.CarPlayNavigationSnapshot
import java.io.Closeable
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** 已确认的启停与路名接口按变化发布；不猜测距离、ETA 或图标编号，不抢原车导航焦点。 */
internal class L7NavigationSession(
    private val port: L7NavigationPort,
    private val current: () -> Boolean,
    private val worker: Executor = workers,
    private val log: (String) -> Unit = L7DebugLog::record,
) : Closeable {
    private val generation = generations.incrementAndGet()
    private val sequence = java.util.concurrent.atomic.AtomicLong()
    private val operations = com.shilapi.xcertplay.diagnostics.DiagnosticOperation({ event(it) })
    private fun event(body: String) {
        runCatching { log("Navigation: generation=$generation event=${sequence.incrementAndGet()} monoMs=${SystemClock.elapsedRealtime()} $body") }
    }
    private val scheduled = AtomicBoolean()
    @Volatile private var latest = CarPlayNavigationSnapshot()
    @Volatile private var closed = false
    private var initialized = false
    private var attempted = false
    private var started = false
    private var publishedRoad: String? = null
    private var connectionToken: Any? = null
    private var failures = 0
    private var reportedAvailability: Boolean? = null
    private var pendingRoad: String? = null

    fun update(value: CarPlayNavigationSnapshot) {
        if (closed || !current()) return
        latest = value
        if (scheduled.compareAndSet(false, true)) enqueue {
            scheduled.set(false)
            if (!closed && current()) publish()
        }
    }
    /** 仅显式调试动作使用；重新核对服务并重发已确认的当前字段，不创建自动重试循环。 */
    fun refresh() {
        if (closed || !current()) return
        enqueue {
            if (closed || !current()) return@enqueue
            publishedRoad = null
            pendingRoad = null
            failures = 0
            publish()
        }
    }
    private fun publish() {
        val value = latest
        if (!value.active && !started) return
        if (!attempted) {
            attempted = true
            initialized = call("initialize") { port.initialize() }
        }
        if (!initialized || closed || !current()) return
        if (!refreshConnection(value.active)) return
        if (!value.active) {
            if (failures < 3) stop()
            if (!started) { pendingRoad = null; failures = 0 }
            return
        }
        val road = value.road.orEmpty().take(256)
        if (pendingRoad != road) { pendingRoad = road; failures = 0 }
        if (failures >= 3) return
        if (!started) {
            // 即使开始调用在远端执行后抛错，退出也要尝试清理。
            started = true
            if (!call("start") { port.start() }) { stop(); failures++; return }
        }
        if (closed || !current()) return
        if (publishedRoad != road) {
            if (call("road") { port.road(road) }) { publishedRoad = road; failures = 0 }
            else failures++
        }
    }
    private fun refreshConnection(active: Boolean): Boolean {
        val token = try { operations.run("serviceReady") { port.connectionToken() } } catch (error: Throwable) {
            if (error !is Exception && error !is LinkageError) throw error
            val cause = if (error is InvocationTargetException) error.targetException else error
            event("stage=serviceReady exceptionType=${cause.javaClass.simpleName}"); null
        }
        if (closed || !current()) return false
        val available = token != null
        if (reportedAvailability != available) {
            reportedAvailability = available
            event("stage=serviceReady result=${if (available) "BINDER_ALIVE" else "WAITING_BINDER"} display=NOT_VERIFIED")
        }
        if (!available) {
            if (connectionToken != null) publishedRoad = null
            connectionToken = null
            return false
        }
        if (connectionToken != token) {
            val result = if (connectionToken == null) "CONNECTED" else "REPLACED"
            publishedRoad = null
            if (active) started = false
            failures = 0
            event("stage=serviceConnection result=$result replayLatest=$active display=NOT_VERIFIED")
        }
        connectionToken = token
        return true
    }
    private fun stop() {
        if (call("stop") { port.stop() }) started = false else failures++
        publishedRoad = null
    }
    private fun call(stage: String, action: () -> Unit): Boolean = try {
        operations.run(stage, action)
        event("stage=$stage result=RETURNED display=NOT_VERIFIED")
        true
    } catch (error: Throwable) {
        if (error !is Exception && error !is LinkageError) throw error
        val cause = if (error is InvocationTargetException) error.targetException else error
        event("stage=$stage exceptionType=${cause.javaClass.simpleName}")
        false
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        port.invalidate()
        latest = CarPlayNavigationSnapshot()
        enqueue { if (started) stop(); call("unregister") { port.close() } }
    }
    private fun enqueue(action: () -> Unit) {
        try { worker.execute(action) }
        catch (error: RuntimeException) {
            scheduled.set(false)
            event("stage=queue exceptionType=${error.javaClass.simpleName}")
        }
    }
    private companion object {
        val generations = java.util.concurrent.atomic.AtomicLong()
        val workers = java.util.concurrent.ThreadPoolExecutor(1, 1, 0, java.util.concurrent.TimeUnit.MILLISECONDS,
            java.util.concurrent.ArrayBlockingQueue(32), { Thread(it, "l7-navigation").apply { isDaemon = true } })
    }
}
