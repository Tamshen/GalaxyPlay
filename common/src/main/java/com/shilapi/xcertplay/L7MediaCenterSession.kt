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
) : Closeable {
    private val generation = generations.incrementAndGet()
    private val events = AtomicLong()
    private val scheduled = AtomicBoolean()
    @Volatile private var closed = false
    @Volatile private var registered = false
    @Volatile private var foreignFocus = false
    private var ownFocus = false
    @Volatile private var latest = CarPlayNowPlaying()
    @Volatile private var artwork: Uri? = null
    private var started = false
    private var ready = false
    private var attempts = 0
    private var published: CarPlayNowPlaying? = null
    private var publishedArtwork: Uri? = null

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
        if (ready == value) return@enqueue
        ready = value
        if (!value) {
            registered = false
            published = null
            safely("unregisterDisconnected") { port.unregister() }
            return@enqueue
        }
        if (attempts >= 3) { event("registerLimit"); return@enqueue }
        attempts++
        try {
            val valid = port.register()
            if (closed || !current()) return@enqueue
            registered = valid
            event("register tokenValid=$registered attempt=$attempts")
            if (!registered) return@enqueue
            val accepted = port.sources(intArrayOf(L7MediaCenterPort.CARPLAY_SOURCE))
            event("sourceList accepted=$accepted source=${L7MediaCenterPort.CARPLAY_SOURCE} origin=CARPLAY_REFERENCE")
            if (closed || !current()) return@enqueue
            if (!accepted) {
                registered = false
                safely("unregisterRejectedSource") { port.unregister() }
                return@enqueue
            }
            safely("queryFocus") { applyFocus(port.focusClient()); "RETURNED" }
            published = null
            publishLatest()
        } catch (error: Exception) {
            registered = false
            failure("register", error)
            safely("unregisterFailed") { port.unregister() }
        }
    }

    fun update(value: CarPlayNowPlaying, uri: Uri? = artwork) {
        if (closed) return
        latest = value
        artwork = uri
        if (scheduled.compareAndSet(false, true)) enqueue {
            scheduled.set(false)
            if (registered) publishLatest()
        }
    }

    private fun publishLatest() {
        if (closed || !current() || !registered) return
        val value = latest
        val uri = artwork
        if (published == value && publishedArtwork == uri) return
        // 只有手机明确进入播放状态时请求一次控制权，进度更新不循环抢源。
        if (value.playbackKnown && value.playing && published?.let { it.playbackKnown && it.playing } != true && !foreignFocus) {
            safely("requestPlay") {
                val accepted = port.requestPlay()
                if (accepted && !closed && current()) { port.currentSource(); event("currentSource returned") }
                accepted
            }
        }
        if (closed || !current()) return
        if (published?.copy(elapsedMillis = null) != value.copy(elapsedMillis = null) || publishedArtwork != uri) {
            if (value.playbackKnown) safely("updateState") { port.update(value, uri) }
            else event("updateState skipped=PHONE_STATE_UNKNOWN")
        }
        val elapsed = value.elapsedMillis
        if (!closed && current() && elapsed != null && elapsed != published?.elapsedMillis)
            safely("updateProgress") { port.progress(elapsed); "RETURNED_MS" }
        published = value
        publishedArtwork = uri
    }

    private fun command(index: Int): Boolean {
        event("callback index=$index registered=$registered phoneKnown=${latest.playbackKnown} playing=${latest.playing}")
        if (closed || !current() || !registered || foreignFocus) { event("drop reason=SESSION_OR_CONTROL_UNAVAILABLE"); return false }
        main.execute {
            if (!closed && current() && registered && !foreignFocus) send(index, "mediacenter")
            else event("drop reason=LATE_CALLBACK")
        }
        return true
    }

    private fun selected(source: Int): Boolean {
        if (closed || !current() || !registered || source != L7MediaCenterPort.CARPLAY_SOURCE) return false
        enqueue { foreignFocus = false; ownFocus = true; safely("selectSource") { port.currentSource(); "RETURNED" } }
        return true
    }

    private fun focus(value: String?) = enqueue { applyFocus(value) }

    private fun applyFocus(value: String?) {
        if (value.isNullOrBlank()) { event("focus known=false"); return }
        val foreign = value != packageName
        event("focus own=${!foreign}")
        val changed = foreign && ownFocus
        ownFocus = !foreign
        foreignFocus = foreign
        if (changed && latest.playbackKnown && latest.playing) main.execute {
            if (!closed && current() && foreignFocus) send(CarPlayMediaButton.PAUSE, "mediacenter-focus")
        }
    }

    private fun enqueue(action: () -> Unit) {
        try { worker.execute { if (!closed && current()) action() } }
        catch (error: RuntimeException) { failure("queue", error) }
    }
    private fun safely(stage: String, action: () -> Any?) {
        try { event("$stage result=${action()}") }
        catch (error: Exception) { failure(stage, error) }
    }
    private fun failure(stage: String, error: Throwable) {
        val cause = if (error is InvocationTargetException) error.targetException else error
        event("$stage exceptionType=${cause.javaClass.simpleName}")
    }
    private fun event(body: String) {
        log("MediaCenter: generation=$generation event=${events.incrementAndGet()} monoMs=${SystemClock.elapsedRealtime()} $body")
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        registered = false
        port.invalidate()
        latest = CarPlayNowPlaying()
        artwork = null
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
