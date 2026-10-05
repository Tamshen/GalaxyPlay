package com.shilapi.xcertplay

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import java.io.Closeable
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** 注册跟随真实手机会话；更新合并，重就绪有限，迟到回调不得向新会话发命令。 */
internal class L7MediaCenterSession(
    private val port: L7MediaCenterPort,
    private val packageName: String,
    private val current: () -> Boolean,
    private val send: (Int, String) -> Unit,
    private val worker: Executor = workers,
    private val main: Executor = Executor { Handler(Looper.getMainLooper()).post(it) },
    private val log: (String) -> Unit = L7DebugLog::record,
    private val traceSend: ((Int, String, L7SteeringTrace) -> Unit)? = null,
    private val retry: (Runnable) -> Unit = { Handler(Looper.getMainLooper()).postDelayed(it, 1000) },
) : Closeable {
    private val generation = generations.incrementAndGet()
    private val events = AtomicLong()
    private val scheduled = AtomicBoolean()
    @Volatile private var closed = false
    @Volatile private var registered = false
    @Volatile private var foreignFocus = false
    private var ownFocus = false
    private data class Snapshot(val value: CarPlayNowPlaying, val artwork: Uri?)
    @Volatile private var snapshot = Snapshot(CarPlayNowPlaying(), null)
    private val latest get() = snapshot.value
    private var started = false
    private var ready = false
    private var attempts = 0
    private var published: CarPlayNowPlaying? = null
    private var publishedArtwork: Uri? = null
    private var publishedProgress: Long? = null
    private var playRequested = false
    private var pending: Snapshot? = null
    private var updateAttempts = 0
    private var registrationRetryScheduled = false
    private var stateRetryScheduled = false

    fun start() = synchronized(this) {
        if (started || closed) return
        started = true
        enqueue {
            event("initialize")
            try {
                port.initialize(::apiReady, ::command, ::focus, ::selected)
                event("initialize result=RETURNED_WAITING_CALLBACK")
            }
            catch (error: Exception) { failure("initialize", error) }
            catch (error: LinkageError) { failure("initialize", error) }
        }
    }

    private fun apiReady(value: Boolean) = enqueue {
        event("apiReady value=$value")
        if (ready == value && (!value || registered)) return@enqueue
        ready = value
        if (!value) {
            registered = false
            published = null
            publishedProgress = null
            playRequested = false
            safely("unregisterDisconnected") { port.unregister() }
            return@enqueue
        }
        if (attempts >= 3) { event("registerLimit"); return@enqueue }
        attempts++
        try {
            snapshot.let { port.prepare(it.value, it.artwork) }
            val valid = port.register()
            if (closed || !current()) return@enqueue
            registered = valid
            event("register tokenValid=$registered attempt=$attempts")
            if (!registered) { retryRegistration(); return@enqueue }
            val accepted = port.sources(intArrayOf(port.source))
            event("sourceList accepted=$accepted source=${port.source} origin=L7_SDK_POLICY")
            if (closed || !current()) return@enqueue
            if (!accepted) {
                registered = false
                safely("unregisterRejectedSource") { port.unregister() }
                retryRegistration()
                return@enqueue
            }
            safely("queryFocus") { applyFocus(port.focusClient()); "RETURNED" }
            published = null
            pending = null
            playRequested = false
            publishLatest()
        } catch (error: Exception) {
            registered = false
            failure("register", error)
            safely("unregisterFailed") { port.unregister() }
        }
    }

    fun update(value: CarPlayNowPlaying, uri: Uri? = snapshot.let {
        if (it.value.artworkTransferId == value.artworkTransferId) it.artwork else null
    }) {
        if (closed) return
        snapshot = Snapshot(value, uri)
        if (scheduled.compareAndSet(false, true)) enqueue {
            scheduled.set(false)
            if (registered) publishLatest()
        }
    }

    private fun publishLatest() {
        if (closed || !current() || !registered) return
        val next = snapshot
        val value = next.value
        val uri = next.artwork
        val metadata = next.copy(value = value.copy(elapsedMillis = null))
        if (pending != metadata) { pending = metadata; updateAttempts = 0 }
        val changed = published?.copy(elapsedMillis = null) != metadata.value || publishedArtwork != uri
        if (changed && !safely("prepare") { port.prepare(value, uri); true }) return
        // 控制权请求与状态缓存分开，失败重发不会因未发布反复抢源。
        if (!value.playbackKnown || !value.playing) playRequested = false
        if (value.playbackKnown && value.playing && !playRequested && !foreignFocus) {
            playRequested = true
            safely("requestPlay") {
                val accepted = port.requestPlay()
                if (accepted && !closed && current()) { port.currentSource(); event("currentSource returned") }
                accepted
            }
        }
        if (closed || !current()) return
        if (changed && value.playbackKnown && !foreignFocus && updateAttempts < 3) {
            updateAttempts++
            event("updateState attempt=$updateAttempts artworkTransferId=${value.artworkTransferId ?: "none"} " +
                "artworkAvailable=${uri != null} coverKey=${uri?.let(L7MediaArtworkProvider::diagnosticKey) ?: "none"}")
            if (safely("updateState") { port.update(value, uri) }) {
                published = value
                publishedArtwork = uri
            } else retryState()
            if (updateAttempts == 3 && published?.copy(elapsedMillis = null) != metadata.value)
                event("updateState retryLimit=3 pending=true")
        }
        val elapsed = value.elapsedMillis
        if (!closed && current() && elapsed != null && elapsed != publishedProgress && !foreignFocus &&
            safely("updateProgress") { port.progress(elapsed); "RETURNED_MS" }) publishedProgress = elapsed
    }

    private fun retryRegistration() {
        if (!ready || registered || attempts >= 3 || registrationRetryScheduled) return
        registrationRetryScheduled = true
        retry(Runnable { enqueue {
            registrationRetryScheduled = false
            if (ready && !registered) apiReady(true)
        } })
    }
    private fun retryState() {
        if (updateAttempts >= 3 || stateRetryScheduled) return
        stateRetryScheduled = true
        retry(Runnable { enqueue {
            stateRetryScheduled = false
            publishLatest()
        } })
    }

    private fun command(index: Int): Boolean {
        val trace = traceSend?.let { L7SteeringDiagnostics.begin("mediacenter", index,
            "registered=$registered foreignFocus=$foreignFocus closed=$closed") }
        event("callback index=$index registered=$registered phoneKnown=${latest.playbackKnown} playing=${latest.playing}")
        val reason = when {
            closed -> "OEM_CLOSED"
            !current() -> "STALE_SESSION"
            !registered -> "OEM_NOT_REGISTERED"
            foreignFocus -> "FOREIGN_FOCUS"
            else -> null
        }
        if (reason != null) { trace?.step("DROP", reason); event("drop reason=SESSION_OR_CONTROL_UNAVAILABLE"); return false }
        trace?.step("OEM_MAIN_QUEUED")
        try { main.execute {
            if (!closed && current() && registered && !foreignFocus) {
                trace?.step("OEM_MAIN_EXECUTE")
                if (trace != null) traceSend?.invoke(index, "mediacenter", trace) else send(index, "mediacenter")
            } else { trace?.step("DROP", "LATE_OEM_CALLBACK"); event("drop reason=LATE_CALLBACK") }
        } } catch (error: RuntimeException) { trace?.step("DROP", "OEM_MAIN_REJECTED"); failure("commandQueue", error); return false }
        return true
    }

    private fun selected(source: Int): Boolean {
        if (closed || !current() || !registered || source != port.source) return false
        enqueue {
            foreignFocus = false; ownFocus = true
            published = null; publishedProgress = null; pending = null
            safely("selectSource") { port.currentSource(); "RETURNED" }
            publishLatest()
        }
        return true
    }

    private fun focus(value: String?) = enqueue { applyFocus(value) }

    private fun applyFocus(value: String?) {
        if (value.isNullOrBlank()) { event("focus known=false"); return }
        val foreign = value != packageName
        event("focus own=${!foreign}")
        val changed = foreign && ownFocus
        val regained = !foreign && foreignFocus
        ownFocus = !foreign
        foreignFocus = foreign
        if (regained) {
            published = null; publishedProgress = null; pending = null
            publishLatest()
        }
        if (changed && latest.playbackKnown && latest.playing) main.execute {
            if (!closed && current() && foreignFocus) send(CarPlayMediaButton.PAUSE, "mediacenter-focus")
        }
    }

    private fun enqueue(action: () -> Unit) {
        try { worker.execute { if (!closed && current()) action() } }
        catch (error: RuntimeException) { scheduled.set(false); failure("queue", error) }
    }
    private fun safely(stage: String, action: () -> Any?): Boolean = try {
        val result = action()
        event("$stage result=$result")
        result != false
    } catch (error: Exception) { failure(stage, error); false }
      catch (error: LinkageError) { failure(stage, error); false }
    private fun failure(stage: String, error: Throwable) {
        val cause = if (error is InvocationTargetException) error.targetException else error
        event("$stage exceptionType=${cause.javaClass.simpleName}")
    }
    private fun event(body: String) {
        if (traceSend != null && !closed && current() && !body.startsWith("updateProgress")) {
            L7SteeringDiagnostics.store.state("oemRegistration", "registered=$registered foreignFocus=$foreignFocus ready=$ready")
            L7SteeringDiagnostics.store.state("mediaCenter", "generation=$generation $body")
        }
        log("MediaCenter: generation=$generation event=${events.incrementAndGet()} monoMs=${SystemClock.elapsedRealtime()} $body")
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        registered = false
        port.invalidate()
        snapshot = Snapshot(CarPlayNowPlaying(), null)
        try { worker.execute {
            safely("clearSources") { port.sources(intArrayOf()) }
            safely("unregister") { port.unregister() }
            safely("release") { port.close(); "RETURNED" }
        } } catch (error: RuntimeException) { failure("cleanupQueue", error) }
    }
    private companion object {
        val generations = AtomicLong()
        val workers = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(32),
            { Thread(it, "l7-mediacenter").apply { isDaemon = true } })
    }
}
