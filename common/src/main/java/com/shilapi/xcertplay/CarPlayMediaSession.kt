package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import java.io.Closeable

/** 协议流存在不代表手机正在播放；手机状态优先，按键恢复不另建焦点请求。 */
internal class CarPlayMediaSession(
    private val context: Context,
    private val onPlaybackStarted: () -> Unit,
    private val send: (Int, String) -> Unit,
) : Closeable {
    private val handler = Handler(Looper.getMainLooper())
    private var audioActive = false
    private var phonePlaying: Boolean? = null
    @Volatile private var closed = false
    internal var session: MediaSession? = null
        private set

    private val callback = CarPlayMediaCallback(explicitHardwareActions = true) { index, source ->
        if (!closed) {
            // 已知手机状态时将切换键转成明确命令，延迟播放不能反向切成暂停。
            val command = if (index == CarPlayMediaButton.PLAY_PAUSE && phonePlaying != null) {
                if (phonePlaying == true) CarPlayMediaButton.PAUSE else CarPlayMediaButton.PLAY
            } else index
            emit("command source=$source index=$command phonePlaying=$phonePlaying streamActive=$audioActive")
            send(command, source)
        }
    }

    /** 当前 CarPlay 窗口收到标准媒体键时走同一命令路径，不交给残留蓝牙媒体会话。 */
    fun onHardwareKey(event: KeyEvent): Boolean {
        if (closed || event.keyCode !in MEDIA_KEYS) return false
        return callback.onMediaButtonEvent(Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, event))
    }

    fun onMediaAudioChanged(active: Boolean) = dispatch {
        audioActive = active
        publish()
        emit("stream active=$active phonePlaying=$phonePlaying")
    }

    fun onIphonePlaying(playing: Boolean) = dispatch {
        val changed = phonePlaying != playing
        phonePlaying = playing
        if (changed && playing) onPlaybackStarted()
        publish()
        emit("phone playback playing=$playing streamActive=$audioActive")
    }

    private fun publish() {
        val playing = phonePlaying ?: audioActive
        if (session == null && (audioActive || playing)) {
            session = MediaSession(context, "L7CarPlay").apply {
                setCallback(callback, handler)
                isActive = true
            }
        }
        session?.setPlaybackState(PlaybackState.Builder().setActions(ACTIONS)
            .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                PlaybackState.PLAYBACK_POSITION_UNKNOWN, if (playing) 1f else 0f).build())
    }

    private fun dispatch(action: () -> Unit) {
        handler.post { synchronized(this) { if (!closed) action() } }
    }

    private fun emit(line: String) {
        Log.i("L7-MediaSession", line)
        L7DebugLog.record("Audio: media $line")
    }

    @Synchronized override fun close() {
        // 先标记关闭，阻止旧手机状态和队列中的按键重新激活会话。
        closed = true
        handler.removeCallbacksAndMessages(null)
        session?.let { it.isActive = false; it.release() }
        session = null
    }

    private companion object {
        val MEDIA_KEYS = setOf(KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK)
        const val ACTIONS = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
    }
}
