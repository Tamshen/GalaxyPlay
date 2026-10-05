package com.shilapi.xcertplay

import android.os.SystemClock
import com.shilapi.xcertplay.hud.CarPlayNavigationSnapshot
import java.io.Closeable
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** 已确认的启停与路名接口按变化发布；不猜测距离、ETA 或图标编号，不抢原车导航焦点。 */
internal class L7NavigationSession(
    private val port: L7NavigationPort,
    private val current: () -> Boolean,
    private val worker: Executor = workers,
    private val log: (String) -> Unit = L7DebugLog::record,
) : Closeable {
    private val scheduled = AtomicBoolean()
    @Volatile private var latest = CarPlayNavigationSnapshot()
    @Volatile private var closed = false
    private var initialized = false
    private var attempted = false
    private var started = false
    private var publishedRoad: String? = null

    fun update(value: CarPlayNavigationSnapshot) {
        if (closed || !current()) return
        latest = value
        if (scheduled.compareAndSet(false, true)) enqueue {
            scheduled.set(false)
            if (!closed && current()) publish()
        }
    }
    private fun publish() {
        val value = latest
        if (!value.active) { if (started) stop(); return }
        if (!attempted) {
            attempted = true
            initialized = call("initialize") { port.initialize() }
        }
        if (!initialized || closed || !current()) return
        if (!started) {
            // 即使开始调用在远端执行后抛错，退出也要尝试清理。
            started = true
            if (!call("start") { port.start() }) { stop(); initialized = false; return }
        }
        if (closed || !current()) return
        val road = value.road.orEmpty().take(256)
        if (publishedRoad != road && call("road") { port.road(road) }) publishedRoad = road
    }
    private fun stop() {
        call("stop") { port.stop() }
        started = false
        publishedRoad = null
    }
    private fun call(stage: String, action: () -> Unit): Boolean = try {
        action()
        log("Navigation: monoMs=${SystemClock.elapsedRealtime()} stage=$stage result=RETURNED display=NOT_VERIFIED")
        true
    } catch (error: Throwable) {
        if (error !is Exception && error !is LinkageError) throw error
        val cause = if (error is InvocationTargetException) error.targetException else error
        log("Navigation: stage=$stage exceptionType=${cause.javaClass.simpleName}")
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
            log("Navigation: stage=queue exceptionType=${error.javaClass.simpleName}")
        }
    }
    private companion object {
        val workers = java.util.concurrent.ThreadPoolExecutor(1, 1, 0, java.util.concurrent.TimeUnit.MILLISECONDS,
            java.util.concurrent.ArrayBlockingQueue(32), { Thread(it, "l7-navigation").apply { isDaemon = true } })
    }
}
