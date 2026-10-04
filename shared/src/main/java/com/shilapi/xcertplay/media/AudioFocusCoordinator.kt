package com.shilapi.xcertplay.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.Closeable

/** 对齐 DiPlay 0.2.11：导航不申请焦点，其余音轨共享一个请求。 */
internal class AudioFocusCoordinator(
    context: Context?, private val enabled: Boolean, private val report: (String) -> Unit = {},
) : Closeable {
    private data class Entry(val channel: AudioChannel, val attributes: AudioAttributes)
    private val manager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val active = LinkedHashMap<AudioTrack, Entry>()
    private var request: AudioFocusRequest? = null
    private var requestedChannel: AudioChannel? = null
    private var generation = 0
    private var hasFocus = false
    private var closed = false

    @Synchronized fun acquire(track: AudioTrack, channel: AudioChannel, attributes: AudioAttributes) {
        if (closed) { runCatching { track.setVolume(0f) }; return }
        if (!enabled || manager == null || channel == AudioChannel.NAVIGATION) return
        active[track] = Entry(channel, attributes)
        refreshRequest()
    }

    @Synchronized fun release(track: AudioTrack) {
        if (active.remove(track) != null) refreshRequest()
    }

    /** 只响应明确的重新播放，失焦回调和重复数据包不能触发持续争抢。 */
    @Synchronized fun resumeMedia() {
        if (closed || hasFocus || active.values.none { it.channel == AudioChannel.MEDIA }) return
        if (active.values.any { it.channel != AudioChannel.MEDIA }) return
        refreshRequest(force = true)
    }

    private fun refreshRequest(force: Boolean = false) {
        val audioManager = manager ?: return
        val primary = active.values.maxByOrNull { it.channel.focusPriority() }
        if (primary == null) { abandon(); return }
        if (!force && request != null && requestedChannel == primary.channel) return
        abandon()
        val currentGeneration = generation
        val gain = when (primary.channel) {
            AudioChannel.MEDIA -> AudioManager.AUDIOFOCUS_GAIN
            AudioChannel.PHONE -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            AudioChannel.ASSISTANT -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            AudioChannel.NAVIGATION -> return
        }
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            synchronized(this) {
                if (closed || generation != currentGeneration || request == null) return@synchronized
                when (change) {
                    AudioManager.AUDIOFOCUS_GAIN -> { hasFocus = true; setVolume(1f) }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> setVolume(0.2f)
                    AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        // 与原版一致：部分车机不回送 GAIN，不能因此永久静音语音。
                        hasFocus = false
                    }
                }
                emit("Audio: focus change=$change channel=$requestedChannel usage=${primary.attributes.usage} policy=diplay-0.2.11 activeTracks=${active.size}")
            }
        }
        val next = AudioFocusRequest.Builder(gain).setAudioAttributes(primary.attributes)
            .setOnAudioFocusChangeListener(listener, Handler(Looper.getMainLooper())).build()
        request = next
        requestedChannel = primary.channel
        val result = runCatching { audioManager.requestAudioFocus(next) }.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        hasFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        emit("Audio: focus requested channel=${primary.channel} usage=${primary.attributes.usage} gain=$gain result=$result policy=diplay-0.2.11")
    }

    private fun setVolume(volume: Float) {
        active.keys.forEach { track -> runCatching { track.setVolume(volume) } }
    }

    // 这是原版的焦点选择顺序；不另叠加 L7 的静音/混音优先级。
    private fun AudioChannel.focusPriority(): Int = when (this) {
        AudioChannel.MEDIA -> 3
        AudioChannel.PHONE -> 2
        AudioChannel.ASSISTANT -> 1
        AudioChannel.NAVIGATION -> 0
    }

    private fun abandon() {
        // 先失效旧监听器，再放弃请求，避免迟到的 GAIN 恢复新会话音量。
        generation++
        val old = request
        request = null
        requestedChannel = null
        hasFocus = false
        old?.let { runCatching { manager?.abandonAudioFocusRequest(it) } }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        setVolume(0f)
        abandon()
        active.clear()
    }

    private fun emit(line: String) {
        Log.i("L7-AudioFocus", line)
        runCatching { report(line) }
    }
}
