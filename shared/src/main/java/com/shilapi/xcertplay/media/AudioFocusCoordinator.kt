package com.shilapi.xcertplay.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log

/** 参考 carlito12345/DiPlay v0.2.11：电话、Siri、导航优先于媒体，保留单一焦点所有者。 */
internal class AudioFocusCoordinator(
    context: Context?,
    private val enabled: Boolean,
    private val report: (String) -> Unit = {},
    private val factoryRouting: Boolean = false,
) : java.io.Closeable {
    private data class Entry(val channel: AudioChannel, val attributes: AudioAttributes)

    private val manager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val active = LinkedHashMap<AudioTrack, Entry>()
    private var request: AudioFocusRequest? = null
    private var requestedChannel: AudioChannel? = null
    private var requestGeneration = 0
    private var focusHeld = false
    private var focusVolume = FULL_VOLUME
    private var mediaAttributes: AudioAttributes? = null
    private var mediaSuppressed = false
    private var closed = false

    private fun onFocusChanged(generation: Int, change: Int) {
        synchronized(this) {
            if (closed || generation != requestGeneration) return
            runCatching { report("Audio: focus change=$change activeTracks=${active.size}") }
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> focusVolume = DUCKED_VOLUME
                AudioManager.AUDIOFOCUS_GAIN -> { focusHeld = true; focusVolume = FULL_VOLUME }
                AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    focusHeld = false
                    if (factoryRouting) focusVolume = 0f
                    if (factoryRouting && change == AudioManager.AUDIOFOCUS_LOSS) mediaSuppressed = true
                }
            }
            applyVolumes()
        }
    }

    @Synchronized
    fun acquire(track: AudioTrack, channel: AudioChannel, attributes: AudioAttributes) {
        if (closed || !enabled || manager == null || (channel == AudioChannel.NAVIGATION && !factoryRouting)) return
        active[track] = Entry(channel, attributes)
        if (channel == AudioChannel.MEDIA) mediaAttributes = attributes
        refreshRequest()
    }

    @Synchronized
    fun release(track: AudioTrack) {
        if (active.remove(track) != null) refreshRequest()
    }

    @Synchronized
    fun onMediaPlaying(playing: Boolean) {
        if (!playing || closed) return
        mediaSuppressed = false
        refreshRequest()
        if (!focusHeld && requestedChannel == AudioChannel.MEDIA) requestCurrentFocus()
    }

    fun resumeMedia() = onMediaPlaying(true)

    /** 读取当前状态不会请求或放弃焦点，用于通话结束后的统计关联。 */
    @Synchronized fun diagnosticState(): String =
        "focusEnabled=$enabled focusGeneration=$requestGeneration focusChannel=${requestedChannel ?: "none"} " +
            "focusHeld=$focusHeld focusVolume=$focusVolume mediaSuppressed=$mediaSuppressed focusTracks=${active.size}"

    @Synchronized
    override fun close() {
        closed = true
        requestGeneration++
        active.keys.forEach { runCatching { it.setVolume(0f) } }
        active.clear()
        mediaAttributes = null
        request?.let { runCatching { manager?.abandonAudioFocusRequest(it) } }
        request = null
        requestedChannel = null
        focusHeld = false
    }

    private fun refreshRequest() {
        val primary = active.values.maxByOrNull { it.channel.focusPriority() }
            ?: mediaAttributes?.takeIf { !mediaSuppressed }?.let { Entry(AudioChannel.MEDIA, it) }
        if (primary == null) {
            requestGeneration++
            request?.let { runCatching { manager?.abandonAudioFocusRequest(it) } }
            request = null
            requestedChannel = null
            focusHeld = false
            focusVolume = FULL_VOLUME
            return
        }
        if (request != null && requestedChannel == primary.channel) { applyVolumes(); return }
        val generation = ++requestGeneration
        request?.let { runCatching { manager?.abandonAudioFocusRequest(it) } }
        val gain = when (primary.channel) {
            AudioChannel.MEDIA -> AudioManager.AUDIOFOCUS_GAIN
            AudioChannel.PHONE -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            AudioChannel.ASSISTANT -> if (factoryRouting) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            AudioChannel.NAVIGATION -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            AudioChannel.RINGTONE -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        }
        val next = AudioFocusRequest.Builder(gain)
            .setAudioAttributes(primary.attributes)
            .setOnAudioFocusChangeListener({ change -> onFocusChanged(generation, change) }, Handler(Looper.getMainLooper()))
            .build()
        request = next
        requestedChannel = primary.channel
        val result = if (factoryRouting && mediaSuppressed && primary.channel == AudioChannel.MEDIA) {
            focusHeld = false
            focusVolume = 0f
            applyVolumes()
            null
        } else requestCurrentFocus()
        val line = "Audio: focus requested channel=${primary.channel} gain=$gain granted=$result activeTracks=${active.size}"
        Log.i(TAG, line)
        runCatching { report(line) }
    }

    private fun requestCurrentFocus(): Int? {
        val current = request ?: return null
        val result = runCatching { manager?.requestAudioFocus(current) }.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        focusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        focusVolume = if (factoryRouting && !focusHeld) 0f else FULL_VOLUME
        applyVolumes()
        return result
    }

    private fun applyVolumes() {
        active.forEach { (track, entry) ->
            val localVolume = if (!factoryRouting || entry.channel == requestedChannel || requestedChannel == AudioChannel.MEDIA) FULL_VOLUME
                else if (requestedChannel == AudioChannel.NAVIGATION && entry.channel == AudioChannel.MEDIA) DUCKED_VOLUME
                else 0f
            val volume = if (factoryRouting && mediaSuppressed && entry.channel == AudioChannel.MEDIA) 0f else focusVolume * localVolume
            runCatching { track.setVolume(volume) }
        }
    }

    private fun AudioChannel.focusPriority(): Int = when (this) {
        AudioChannel.PHONE -> 4
        AudioChannel.ASSISTANT, AudioChannel.RINGTONE -> 3
        AudioChannel.NAVIGATION -> 2
        AudioChannel.MEDIA -> 1
    }

    private companion object {
        const val TAG = "DiPlay-AudioFocus"
        const val FULL_VOLUME = 1f
        const val DUCKED_VOLUME = 0.2f
    }
}
