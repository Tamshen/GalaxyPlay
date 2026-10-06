package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.airplay.CarPlayMediaButton

internal object CarPlayBackgroundSession {
    @Volatile var active = false
    private var stopAction: (((() -> Unit)) -> Unit)? = null
    private var stopping = false
    private var owner: Any? = null
    @Synchronized fun isOwner(candidate: Any): Boolean = owner === candidate
    @Synchronized fun hasSession(): Boolean = stopAction != null || stopping
    @Synchronized fun isStopping(): Boolean = stopping
    private val stopWaiters = mutableListOf<() -> Unit>()

    fun stop(completion: () -> Unit = {}) {
        L7StartupGuard.stopped()
        val action: (((() -> Unit)) -> Unit)?
        synchronized(this) {
            if (stopping) { stopWaiters.add(completion); return }
            action = stopAction
            if (action != null) { stopping = true; stopWaiters.add(completion) }
        }
        if (action == null) { completion(); return }
        action.invoke {
            val callbacks = synchronized(this) {
                stopping = false
                stopWaiters.toList().also { stopWaiters.clear() }
            }
            callbacks.forEach { it() }
        }
    }

    data class Snapshot(
        val controller: CarPlayController,
        val sink: AndroidMediaSink,
        val width: Int,
        val height: Int,
        val display: CarPlaySessionDisplay?,
    )

    private var controller: CarPlayController? = null
    private var sink: AndroidMediaSink? = null
    private var bluetoothMediaGuard: L7BluetoothMediaGuard? = null
    private var width = 0
    private var height = 0
    private var display: CarPlaySessionDisplay? = null

    @Synchronized
    fun snapshot(): Snapshot? {
        val currentController = controller ?: return null
        val currentSink = sink ?: return null
        return Snapshot(currentController, currentSink, width, height, display)
    }

    @Synchronized
    fun store(controller: CarPlayController, sink: AndroidMediaSink, width: Int, height: Int, owner: Any,
              display: CarPlaySessionDisplay? = null, stop: (() -> Unit) -> Unit) {
        L7ReportingTests.stop("CARPLAY_SESSION")
        if (this.controller !== controller) {
            bluetoothMediaGuard?.close()
            bluetoothMediaGuard = null
            L7BluetoothAudioSettings.status = BluetoothMediaStatus.IDLE
        }
        this.stopAction = stop
        this.owner = owner
        this.controller = controller
        this.sink = sink
        this.width = width
        this.height = height
        this.display = display
    }

    @Synchronized
    fun clear(expected: CarPlayController? = null, keepOwner: Boolean = false) {
        if (expected != null && controller !== expected) return
        bluetoothMediaGuard?.close()
        bluetoothMediaGuard = null
        controller = null
        sink = null
        if (!keepOwner) { stopAction = null; owner = null }
        active = false
        width = 0
        height = 0
        display = null
    }

    /** 与后台控制器共存，切换设置或重建窗口不重复断开；结束会话才释放监听。 */
    @Synchronized fun setBluetoothMediaActive(expected: CarPlayController, context: Context, active: Boolean) {
        if (controller !== expected) return
        if (active && (expected.isClosed() || !expected.localMediaAudioEnabled)) return
        if (!active) {
            bluetoothMediaGuard?.close()
            bluetoothMediaGuard = null
            return
        }
        if (bluetoothMediaGuard != null) return
        bluetoothMediaGuard = L7BluetoothMediaGuard(expected.activeBluetoothDeviceAddress,
            AirPlayPersistence.loadBluetoothMediaExclusive(context), AndroidBluetoothMediaPort(context.applicationContext)) {
            L7BluetoothAudioSettings.status = it
        }.also { it.start() }
    }

    /** 设置页更新当前会话的既有保护器；不创建会话或重置尝试预算。 */
    @Synchronized fun updateBluetoothMediaAutomatic(enabled: Boolean) {
        bluetoothMediaGuard?.setAutomatic(enabled)
    }

    /** 手机播放命令与 A2DP 断开确认协调；暂停取消等待，旧控制器不能恢复播放。 */
    fun beforeMediaCommand(expected: CarPlayController, index: Int, action: () -> Unit, dropped: (String) -> Unit = {}) {
        val guard = synchronized(this) {
            if (controller !== expected || expected.isClosed()) { dropped("STALE_CONTROLLER"); return }
            bluetoothMediaGuard
        }
        if (index == CarPlayMediaButton.PLAY && guard != null) guard.beforePlay(action, dropped)
        else {
            if (index == CarPlayMediaButton.PAUSE || index == CarPlayMediaButton.PLAY_PAUSE) guard?.cancelPendingPlay()
            action()
        }
    }
}
