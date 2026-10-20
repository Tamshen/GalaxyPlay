package com.shilapi.xcertplay

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.hud.CarPlayNavigationSnapshot
import java.io.Closeable
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** 注册与回调均在同一工作队列处理；等待服务时只保留最新快照，不堆积路线数据。 */
internal class GalaxyOemNavigation(
    context: Context,
    private val log: (String) -> Unit = L7DebugLog::record,
    private val worker: Executor = workers,
    private val delay: (Long, () -> Unit) -> Closeable = ::later,
    backendFactory: ((() -> CarPlayNavigationSnapshot?) -> GalaxyNavigationBackend)? = null,
) : L7NavigationPort {
    override val transport = "OEM_MAP_API"
    private val factory = backendFactory ?: { snapshot -> GalaxyNavigationSdk(context.applicationContext, snapshot) }
    @Volatile private var valid = true
    @Volatile private var latest = CarPlayNavigationSnapshot()
    private val scheduled = AtomicBoolean()
    private val closing = AtomicBoolean()
    private var backend: GalaxyNavigationBackend? = null
    private var timer: Closeable? = null
    private var epoch = 0L
    private var attempts = 0
    private var ready = false
    private var registered = false
    private var vendor = 0
    private val listeners = mutableListOf<GalaxyNavigationListener>()
    private var started = false
    private var replayStart = true

    override fun initialize() = enqueue {
        if (!valid || owner === this) return@enqueue
        owner?.let { it.valid = false; it.dispose() }
        owner = this
        connect()
    }
    override fun start() = enqueue {
        if (!owns()) return@enqueue
        if (backend == null && attempts >= MAX_ATTEMPTS) { attempts = 0; connect() }
        // 手动刷新允许重新发送开始状态，但不重复注册健康客户端。
        replayStart = true
    }
    override fun guidance(value: CarPlayNavigationSnapshot) {
        if (!valid) return
        latest = value
        flushLater()
    }
    override fun stop() {
        latest = CarPlayNavigationSnapshot()
        flushLater()
    }
    override fun invalidate() { valid = false; latest = CarPlayNavigationSnapshot() }
    override fun close() = close {}
    override fun close(completed: () -> Unit) {
        invalidate()
        if (!closing.compareAndSet(false, true)) { completed(); return }
        enqueue {
            try {
                if (owner === this) { dispose(); owner = null }
            } finally { completed() }
        }
    }

    private fun connect() {
        if (!owns() || attempts >= MAX_ATTEMPTS) return
        attempts++
        val generation = ++epoch
        event("stage=initialize attempt=$attempts")
        try {
            val service = factory { latest.takeIf { valid && it.active } }
            backend = service
            timer?.close()
            timer = delay(10_000) { enqueue {
                if (owns() && epoch == generation && (!registered || listeners.isEmpty())) recover("callbackTimeout")
            } }
            service.initialize { available -> enqueue {
                if (!owns() || epoch != generation) return@enqueue
                if (!available) { recover("apiDisconnected"); return@enqueue }
                if (ready) return@enqueue
                ready = true
                register(service, generation)
            } }
        } catch (error: Throwable) { failed("initialize", error) }
    }
    private fun register(service: GalaxyNavigationBackend, generation: Long) {
        try {
            vendor = service.register { listener -> enqueue {
                if (!owns() || epoch != generation) return@enqueue
                // 原厂可以交付多个消费者；每个注册代次限定数量，重连先清空旧监听。
                if (listeners.size >= 8) { event("stage=listener result=LIMIT_REACHED"); return@enqueue }
                listeners += listener
                replayStart = true
                timer?.close(); timer = null
                event("stage=listener result=ATTACHED count=${listeners.size}")
                flush()
            } }
            registered = true
            event("stage=register result=ACCEPTED")
            if (listeners.isNotEmpty()) flush()
        } catch (error: Throwable) { failed("register", error) }
    }
    private fun flushLater() {
        if (!scheduled.compareAndSet(false, true)) return
        enqueue {
            scheduled.set(false)
            if (owns()) flush()
        }
    }
    private fun flush() {
        if (!registered || listeners.isEmpty()) return
        try {
            val value = latest
            if (!value.active) {
                if (started) sendStatus(17)
                started = false
                return
            }
            if (!started || replayStart) {
                // 即使某个监听抛错，结束时也尝试撤回已经送达其他监听的开始状态。
                started = true
                sendStatus(16)
                replayStart = false
            }
            if (!owns()) return
            listeners.forEach { it.guidance(value, vendor) }
            attempts = 0
            event("stage=guidance result=CALL_RETURNED listeners=${listeners.size}")
        } catch (error: Throwable) { failed("publish", error) }
    }
    private fun sendStatus(state: Int) {
        var failure: Throwable? = null
        listeners.forEach {
            try { it.status(state) }
            catch (error: Exception) { if (failure == null) failure = error }
            catch (error: LinkageError) { if (failure == null) failure = error }
        }
        failure?.let { throw it }
        event("stage=status value=$state result=CALL_RETURNED")
    }
    private fun failed(stage: String, error: Throwable) {
        val cause = if (error is InvocationTargetException) error.targetException else error
        if (cause !is Exception && cause !is LinkageError) throw cause
        val code = (cause as? GalaxyNavigationRegistrationException)?.code
        event("stage=$stage result=FAILED exceptionType=${cause.javaClass.simpleName}${code?.let { " code=$it" }.orEmpty()}")
        recover(stage)
    }
    private fun recover(reason: String) {
        dispose()
        if (!owns()) return
        if (attempts >= MAX_ATTEMPTS) { event("stage=recovery result=EXHAUSTED"); return }
        val generation = epoch
        event("stage=recovery reason=$reason result=SCHEDULED")
        timer = delay(1_000) { enqueue { if (owns() && epoch == generation) connect() } }
    }
    private fun dispose() {
        epoch++
        timer?.close(); timer = null
        if (started) try { sendStatus(17) } catch (error: Exception) {
            event("stage=stop result=FAILED exceptionType=${error.javaClass.simpleName}")
        } catch (error: LinkageError) { event("stage=stop result=FAILED exceptionType=${error.javaClass.simpleName}") }
        started = false
        replayStart = true
        registered = false
        ready = false
        listeners.clear()
        val old = backend
        backend = null
        try { old?.close() } catch (error: Exception) {
            event("stage=release result=FAILED exceptionType=${error.javaClass.simpleName}")
        } catch (error: LinkageError) { event("stage=release result=FAILED exceptionType=${error.javaClass.simpleName}") }
        if (old != null) event("stage=release result=FINISHED remoteClear=NOT_VERIFIED")
    }
    private fun owns() = valid && owner === this
    private fun event(message: String) {
        runCatching { log("Navigation: transport=$transport generation=$epoch $message reception=NOT_VERIFIED display=NOT_VERIFIED") }
    }
    private fun enqueue(action: () -> Unit) {
        try { worker.execute(action) }
        catch (error: RuntimeException) {
            scheduled.set(false)
            event("stage=queue result=FAILED exceptionType=${error.javaClass.simpleName}")
            throw error
        }
    }
    companion object {
        private const val MAX_ATTEMPTS = 3
        // 同进程的手动样例与真实导航共用 SDK 单例；新拥有者接入前先释放旧拥有者。
        private var owner: GalaxyOemNavigation? = null
        private val workers = java.util.concurrent.ThreadPoolExecutor(1, 1, 0, java.util.concurrent.TimeUnit.MILLISECONDS,
            java.util.concurrent.ArrayBlockingQueue(64), { Thread(it, "galaxy-map-api").apply { isDaemon = true } })
        private fun later(ms: Long, action: () -> Unit): Closeable {
            val handler = Handler(Looper.getMainLooper())
            val task = Runnable(action)
            handler.postDelayed(task, ms)
            return Closeable { handler.removeCallbacks(task) }
        }
    }
}
