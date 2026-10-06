package com.shilapi.xcertplay.media

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRouting
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.Closeable

/** 会话拥有监听器；关闭绑定后，迟到回调不能操作已释放的播放/录音对象。 */
internal class L7AudioRouting(context: Context?, private val preferBus: Boolean = false,
                              private val template: AudioRoutingTemplate? = null,
                              private val report: (String) -> Unit) : Closeable {
    private val manager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private val bindings = mutableSetOf<Binding>()
    private var listening = false
    private var closed = false
    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }

    inner class Binding internal constructor(
        private val routing: AudioRouting,
        private val channel: AudioChannel,
        private val input: Boolean,
        private val sampleRate: Int,
        private val channels: Int,
        private val useBus: Boolean,
    ) : Closeable {
        private var released = false
        private var attached = false
        private var lastActual: String? = null
        private val listener = AudioRouting.OnRoutingChangedListener { reportActual() }

        internal fun attach() {
            runCatching { routing.addOnRoutingChangedListener(listener, handler); attached = true }
                .onFailure { emit("Audio: routing listener unavailable error=${it.javaClass.simpleName}") }
            select()
        }

        internal fun select() {
            if (released || closed) return
            if (!preferBus || !useBus || input) {
                // 默认、传统流与录音只观察实际路由，不向系统写入首选设备。
                emit("Audio: route policy=system channel=$channel direction=${if (input) "input" else "output"}")
                reportActual()
                return
            }
            val devices = runCatching {
                manager?.getDevices(if (input) AudioManager.GET_DEVICES_INPUTS else AudioManager.GET_DEVICES_OUTPUTS)
                    ?.toList().orEmpty()
            }.getOrElse { emptyList() }
            val selected = if (useBus) runCatching { L7AudioRoutePolicy.select(devices.map {
                L7AudioDevice(it.id, it.address, input, it.sampleRates.toList(), it.channelCounts.toList())
            }, channel, input, sampleRate, channels, template) }.getOrNull() else null
            val target = selected?.let { match -> devices.firstOrNull { it.id == match.id } }
            val accepted = runCatching { routing.setPreferredDevice(target) }.getOrDefault(false)
            // 请求被拒绝时清除旧偏好，不能带着失效设备 ID 继续播放。
            val fallback = if (!accepted && target != null) {
                runCatching { routing.setPreferredDevice(null) }.getOrDefault(false)
            } else target == null && accepted
            emit("Audio: route request channel=$channel direction=${if (input) "input" else "output"} " +
                "candidate=${if (useBus) L7AudioRoutePolicy.candidate(channel, input, sampleRate, template) else "legacy-override"} " +
                "preferredId=${target?.id ?: -1} accepted=$accepted systemFallback=$fallback")
            reportActual()
        }

        fun reportActual() = synchronized(this@L7AudioRouting) {
            if (released || closed) return@synchronized
            val device = runCatching { routing.routedDevice }.getOrNull()
            // 仅记录固件配置中确认的 BUS；观察真实路由不等于请求或进入头枕。
            val bus = runCatching { device?.address?.trim() }.getOrNull()?.takeIf {
                L7AudioRoutePolicy.knownBus(it) || template?.knownBus(it) == true
            } ?: "system-or-unknown"
            val actual = "id=${device?.id ?: -1} type=${device?.type ?: -1} bus=$bus"
            if (lastActual != actual) {
                lastActual = actual
                emit("Audio: route actual channel=$channel direction=${if (input) "input" else "output"} $actual")
            }
        }

        override fun close() = synchronized(this@L7AudioRouting) {
            if (released) return@synchronized
            released = true
            bindings.remove(this)
            if (attached) runCatching { routing.removeOnRoutingChangedListener(listener) }
            attached = false
            if (bindings.isEmpty()) stopListening()
        }
    }

    @Synchronized
    fun bind(routing: AudioRouting, channel: AudioChannel, input: Boolean,
             sampleRate: Int, channels: Int, useBus: Boolean = true): Binding {
        val binding = Binding(routing, channel, input, sampleRate, channels, useBus)
        if (closed) { binding.close(); return binding }
        bindings.add(binding)
        val audioManager = manager
        if (!listening && audioManager != null) {
            listening = runCatching { audioManager.registerAudioDeviceCallback(callback, handler); true }
                .getOrDefault(false)
        }
        binding.attach()
        return binding
    }

    @Synchronized private fun refresh() {
        if (!closed) bindings.toList().forEach { it.select() }
    }

    private fun stopListening() {
        if (listening) runCatching { manager?.unregisterAudioDeviceCallback(callback) }
        listening = false
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        bindings.toList().forEach { it.close() }
        stopListening()
    }

    private fun emit(line: String) {
        Log.i("L7-AudioRouting", line)
        runCatching { report(line) }
    }
}
