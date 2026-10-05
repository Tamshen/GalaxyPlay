package com.shilapi.xcertplay

import android.bluetooth.BluetoothAdapter
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.Closeable

internal enum class BluetoothMediaStatus { IDLE, CHECKING, NO_TARGET, CLEAR, CONFLICT, DISCONNECTING, BLOCKED, LIMIT, CLOSED }

/** 会话接通后有限处理目标手机的蓝牙媒体；请求成功与实际断开分开确认。 */
internal class L7BluetoothMediaGuard(
    private val address: String?,
    private var automatic: Boolean,
    private val port: L7BluetoothMediaPort,
    private val status: (BluetoothMediaStatus) -> Unit,
) : Closeable {
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var closed = false
    @Volatile private var failed = false
    private var started = false
    private var ready = false
    private var waiting = false
    private var attempts = 0
    private var generation = 0
    private var lastStatus: BluetoothMediaStatus? = null
    private var pendingPlay: (() -> Unit)? = null

    /** 明确播放等待媒体链路释放，重复点击只保留最后一次；失败走现有手动降级。 */
    fun beforePlay(action: () -> Unit) = playDispatch {
        if (closed) return@playDispatch
        pendingPlay = action
        emit("explicit play waiting status=$lastStatus")
        if (failed || !automatic || lastStatus in PLAY_FALLBACK) flushPlay()
        else if (ready) inspect()
    }

    /** 暂停应立即生效，同时取消尚未送往手机的播放。 */
    fun cancelPendingPlay() = playDispatch {
        if (pendingPlay != null) emit("pending play cancelled")
        pendingPlay = null
    }

    private fun playDispatch(action: () -> Unit) {
        if (Looper.myLooper() == handler.looper) action() else handler.post { action() }
    }

    /** 当前会话即时响应设置；撤销等待不撤销已送出的系统请求，也不重置尝试预算。 */
    fun setAutomatic(enabled: Boolean) = playDispatch {
        if (closed || automatic == enabled) return@playDispatch
        automatic = enabled
        generation++
        waiting = false
        emit("automatic changed enabled=$enabled attempts=$attempts")
        if (!failed && ready) inspect()
        else if (!enabled) flushPlay()
    }

    fun start() = dispatch {
        if (started) return@dispatch
        started = true
        if (address == null || !BluetoothAdapter.checkBluetoothAddress(address)) {
            publish(BluetoothMediaStatus.NO_TARGET)
            return@dispatch
        }
        publish(BluetoothMediaStatus.CHECKING)
        try {
            val accepted = port.open(
                ready = { dispatch { ready = true; inspect() } },
                changed = { dispatch { inspect() } },
            )
            if (!accepted) unavailable("profile_open_rejected")
            else handler.postDelayed({
                if (!closed && !ready) unavailable("profile_timeout")
            }, 6000)
        } catch (error: Exception) { unavailable(error.javaClass.simpleName) }
    }

    private fun inspect() {
        if (!ready || closed) return
        try {
            val current = port.state(address!!)
            if (!current.connected) { waiting = false; publish(BluetoothMediaStatus.CLEAR); return }
            if (waiting) return
            emit("connected=true playing=${current.playing} automatic=$automatic attempts=$attempts")
            if (!automatic) { publish(BluetoothMediaStatus.CONFLICT); return }
            if (attempts >= MAX_ATTEMPTS) { publish(BluetoothMediaStatus.LIMIT); return }
            attempts++
            waiting = true
            publish(BluetoothMediaStatus.DISCONNECTING)
            val accepted = port.disconnect(address!!)
            emit("disconnect requested accepted=$accepted attempt=$attempts")
            if (!accepted) { unavailable("disconnect_rejected"); return }
            val epoch = ++generation
            handler.postDelayed({ if (generation == epoch && automatic) confirmDisconnect() }, 3000)
        } catch (error: Exception) { unavailable(error.javaClass.simpleName) }
    }

    private fun confirmDisconnect() {
        if (closed || !waiting) return
        try {
            if (port.state(address!!).connected) unavailable("disconnect_not_confirmed")
            else { waiting = false; publish(BluetoothMediaStatus.CLEAR) }
        } catch (error: Exception) { unavailable(error.javaClass.simpleName) }
    }

    private fun unavailable(reason: String) {
        failed = true
        ready = false
        waiting = false
        handler.removeCallbacksAndMessages(null)
        port.close()
        emit("unavailable reason=$reason manual_media_audio_required=true")
        publish(BluetoothMediaStatus.BLOCKED)
    }

    private fun publish(next: BluetoothMediaStatus) {
        if (next == lastStatus) {
            if (next == BluetoothMediaStatus.CLEAR || next in PLAY_FALLBACK) flushPlay()
            return
        }
        lastStatus = next
        emit("status=$next")
        status(next)
        if (next == BluetoothMediaStatus.CLEAR || next in PLAY_FALLBACK) flushPlay()
    }

    private fun flushPlay() {
        val action = pendingPlay
        pendingPlay = null
        if (!closed && action != null) {
            emit("explicit play dispatched status=$lastStatus")
            action()
        }
    }

    private fun emit(line: String) {
        // 不输出蓝牙名称/MAC，暂停与互斥状态可在悬浮日志查阅。
        Log.i("L7-BluetoothMedia", line)
        L7DebugLog.record("Audio: Bluetooth media $line")
    }

    private fun dispatch(action: () -> Unit) { handler.post { if (!closed && !failed) action() } }

    override fun close() {
        closed = true
        pendingPlay = null
        handler.removeCallbacksAndMessages(null)
        val cleanup = Runnable { port.close(); publish(BluetoothMediaStatus.CLOSED) }
        if (Looper.myLooper() == handler.looper) cleanup.run() else handler.post(cleanup)
    }

    private companion object {
        const val MAX_ATTEMPTS = 2
        val PLAY_FALLBACK = setOf(BluetoothMediaStatus.NO_TARGET, BluetoothMediaStatus.CONFLICT,
            BluetoothMediaStatus.BLOCKED, BluetoothMediaStatus.LIMIT)
    }
}
