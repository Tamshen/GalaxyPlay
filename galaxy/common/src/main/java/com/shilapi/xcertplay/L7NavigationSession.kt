package com.shilapi.xcertplay

import android.os.SystemClock
import com.shilapi.xcertplay.hud.CarPlayNavigationSnapshot
import java.io.Closeable
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** 单线程发布导航快照；保留最新状态、有限失败预算及会话结束清理。 */
internal class L7NavigationSession(
    private val port: L7NavigationPort,
    private val current: () -> Boolean,
    private val worker: Executor = workers,
    private val log: (String) -> Unit = L7DebugLog::record,
    private val now: () -> Long = SystemClock::elapsedRealtime,
) : Closeable {
    private val generation = generations.incrementAndGet()
    private val sequence = java.util.concurrent.atomic.AtomicLong()
    private val operations = com.shilapi.xcertplay.diagnostics.SampledDiagnosticOperation({ event(it) }, emptySet())
    private val scheduled = AtomicBoolean()
    @Volatile private var latest = CarPlayNavigationSnapshot()
    @Volatile private var closed = false
    private var attempted = false
    private var initialized = false
    private var started = false
    private var startConfirmed = false
    private var published: CarPlayNavigationSnapshot? = null
    private var pending: CarPlayNavigationSnapshot? = null
    private var publishedAt = 0L
    private var failures = 0

    fun update(value: CarPlayNavigationSnapshot) {
        if (closed || !current()) return
        latest = value
        if (scheduled.compareAndSet(false, true)) enqueue {
            scheduled.set(false)
            if (!closed && current()) publish()
        }
    }
    /** 显式刷新重发开始状态与整份样例，供接收端重启后的手动复查。 */
    fun refresh() {
        if (closed || !current()) return
        enqueue {
            if (closed || !current()) return@enqueue
            published = null
            pending = null
            failures = 0
            if (latest.active) startConfirmed = false
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
        if (pending != value) { pending = value; failures = 0 }
        if (failures >= 3) return
        if (!value.active) { stop(); return }
        if (!startConfirmed) {
            // 开始状态可能已经发出后才抛错，退出仍须尝试发送结束状态。
            started = true
            if (!call("start") { port.start() }) { failures++; return }
            startConfirmed = true
        }
        if (closed || !current()) return
        if (published != value || now() - publishedAt >= 5_000) {
            if (call("guidance") { port.guidance(value) }) {
                published = value
                publishedAt = now()
                failures = 0
            } else failures++
        }
    }
    private fun stop() {
        startConfirmed = false
        if (call("stop") { port.stop() }) { started = false; startConfirmed = false; failures = 0 } else failures++
        published = null
    }
    private fun event(body: String) {
        runCatching { log("Navigation: transport=${port.transport} generation=$generation event=${sequence.incrementAndGet()} monoMs=${now()} $body") }
    }
    private fun call(stage: String, action: () -> Unit): Boolean = try {
        operations.run(stage, action)
        event("stage=$stage result=RETURNED reception=NOT_VERIFIED display=NOT_VERIFIED")
        true
    } catch (error: Throwable) {
        if (error !is Exception && error !is LinkageError) throw error
        event("stage=$stage exceptionType=${error.javaClass.simpleName}")
        false
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        port.invalidate()
        latest = CarPlayNavigationSnapshot()
        enqueue {
            operations.flush()
            if (started) stop()
            call("release") { port.close() }
        }
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
