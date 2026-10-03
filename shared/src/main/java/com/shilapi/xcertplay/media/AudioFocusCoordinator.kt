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

/** 一个会话共用焦点请求；电话优先于 Siri、导航和音乐，结束后重新选择剩余流。 */
internal class AudioFocusCoordinator(
    context: Context?, private val enabled: Boolean, private val report: (String) -> Unit = {},
) : Closeable {
    private data class Entry(val channel: AudioChannel, val attributes: AudioAttributes)
    private val manager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val active = LinkedHashMap<AudioTrack, Entry>()
    private var request: AudioFocusRequest? = null
    private var requestedChannel: AudioChannel? = null
    private var generation = 0
    private var allowed = false
    private var ducked = false
    private var closed = false

    @Synchronized fun acquire(track: AudioTrack, channel: AudioChannel, attributes: AudioAttributes) {
        if (closed) { runCatching { track.setVolume(0f) }; return }
        active[track] = Entry(channel, attributes)
        refreshRequest()
        applyVolumes()
    }

    @Synchronized fun release(track: AudioTrack) {
        if (active.remove(track) != null) { refreshRequest(); applyVolumes() }
    }

    /** 只响应明确的重新播放，失焦回调和重复数据包不能触发持续争抢。 */
    @Synchronized fun resumeMedia() {
        if (closed || allowed || active.values.none { it.channel == AudioChannel.MEDIA }) return
        if (active.values.any { it.channel != AudioChannel.MEDIA }) return
        refreshRequest(force = true)
        applyVolumes()
    }

    private fun refreshRequest(force: Boolean = false) {
        val audioManager = manager
        val primary = active.values.maxByOrNull { L7AudioMixPolicy.priority(it.channel) }
        if (primary == null) { abandon(); return }
        if (!enabled || audioManager == null) { allowed = true; return }
        if (!force && request != null && requestedChannel == primary.channel) return
        abandon()
        val currentGeneration = generation
        val gain = when (primary.channel) {
            AudioChannel.MEDIA -> AudioManager.AUDIOFOCUS_GAIN
            AudioChannel.PHONE, AudioChannel.ASSISTANT -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            AudioChannel.NAVIGATION -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        }
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            synchronized(this) {
                if (closed || generation != currentGeneration || request == null) return@synchronized
                when (change) {
                    AudioManager.AUDIOFOCUS_GAIN -> { allowed = true; ducked = false }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> { allowed = true; ducked = true }
                    AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        allowed = false; ducked = false
                    }
                }
                // 静音期间继续消耗有界实时流，恢复焦点不补播旧导航/通话声音。
                applyVolumes()
                emit("Audio: focus change=$change channel=$requestedChannel usage=${primary.attributes.usage} allowed=$allowed activeTracks=${active.size}")
            }
        }
        val next = AudioFocusRequest.Builder(gain).setAudioAttributes(primary.attributes)
            .setOnAudioFocusChangeListener(listener, Handler(Looper.getMainLooper())).build()
        request = next
        requestedChannel = primary.channel
        applyVolumes()
        val result = runCatching { audioManager.requestAudioFocus(next) }.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        allowed = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        emit("Audio: focus requested channel=${primary.channel} usage=${primary.attributes.usage} gain=$gain result=$result allowed=$allowed")
    }

    private fun applyVolumes() {
        val channels = active.values.map { it.channel }
        active.forEach { (track, entry) ->
            val volume = L7AudioMixPolicy.volume(entry.channel, channels, allowed, ducked)
            runCatching { track.setVolume(volume) }
        }
    }

    private fun abandon() {
        // 先失效旧监听器，再放弃请求，避免迟到的 GAIN 恢复新会话音量。
        generation++
        val old = request
        request = null
        requestedChannel = null
        allowed = false
        ducked = false
        old?.let { runCatching { manager?.abandonAudioFocusRequest(it) } }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        allowed = false
        applyVolumes()
        abandon()
        active.clear()
    }

    private fun emit(line: String) {
        Log.i("L7-AudioFocus", line)
        runCatching { report(line) }
    }
}
