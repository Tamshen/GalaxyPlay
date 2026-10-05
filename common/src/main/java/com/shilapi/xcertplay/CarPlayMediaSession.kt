package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.os.SystemClock
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import java.util.concurrent.Executor
import java.util.concurrent.Executors
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
    private var nowPlaying = CarPlayNowPlaying()
    private var elapsedUpdatedAt = 0L
    private var artwork: Bitmap? = null
    private val artworkCache = LinkedHashMap<Int, Bitmap?>()
    private val artworkQueue = NowPlayingArtworkQueue(
        worker = artworkWorker,
        main = Executor { handler.post(it) },
        decode = NowPlayingMetadata::decodeArtwork,
        publish = ::onArtworkDecoded,
        discard = Bitmap::recycle,
    )
    private var artworkOwner: Any? = artworkQueue.newSession()
    private var connected = false
    private var connectionKnown = false
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
        return callback.onKey(event, "window-key")
    }

    /** 已连接但尚未播放也可接收标准控制；不伪报手机播放，不另抢音频焦点。 */
    fun onConnected(value: Boolean) = dispatch {
        connectionKnown = true
        connected = value
        if (!value) {
            session?.let { it.isActive = false; it.release() }
            session = null
            audioActive = false
            phonePlaying = null
            nowPlaying = CarPlayNowPlaying()
            artworkOwner = artworkQueue.newSession()
            artworkCache.clear()
            artwork = null
        } else publish()
        emit("connection active=$value")
    }

    fun onMediaAudioChanged(active: Boolean) = dispatch {
        if (connectionKnown && !connected) return@dispatch
        audioActive = active
        publish()
        emit("stream active=$active phonePlaying=$phonePlaying")
    }

    fun onIphonePlaying(playing: Boolean) = dispatch {
        if (connectionKnown && !connected) return@dispatch
        val changed = phonePlaying != playing
        phonePlaying = playing
        if (changed && playing) onPlaybackStarted()
        publish()
        emit("phone playback playing=$playing streamActive=$audioActive")
    }

    fun onNowPlayingChanged(update: CarPlayNowPlaying) = dispatch {
        if (connectionKnown && !connected) return@dispatch
        val previousArtwork = artwork
        if (update == CarPlayNowPlaying()) {
            artworkOwner = artworkQueue.newSession()
            artworkCache.clear()
            artwork = null
        } else if (nowPlaying.artworkTransferId != update.artworkTransferId) {
            artwork = update.artworkTransferId?.let { artworkCache[it] }
        }
        if (nowPlaying.elapsedMillis != update.elapsedMillis || nowPlaying.playing != update.playing) elapsedUpdatedAt = SystemClock.elapsedRealtime()
        val metadataChanged = NowPlayingMetadata.metadataChanged(nowPlaying, update) || artwork !== previousArtwork
        nowPlaying = update
        if (update.playbackKnown && phonePlaying != update.playing) {
            phonePlaying = update.playing
            if (update.playing) onPlaybackStarted()
        }
        // 进度变化只发布播放状态，不反复将同一封面复制到系统服务。
        if (metadataChanged) session?.setMetadata(NowPlayingMetadata.androidMetadata(update, artwork))
        publish()
    }

    @Synchronized fun onArtworkChanged(id: Int, bytes: ByteArray) {
        if (!closed) artworkOwner?.let { artworkQueue.submit(it, id, bytes) }
    }

    @Synchronized private fun onArtworkDecoded(expected: Any, id: Int, decoded: Bitmap?) {
        if (closed || artworkOwner !== expected) {
            decoded?.recycle()
            return
        }
        artworkCache.remove(id)
        artworkCache[id] = decoded
        while (artworkCache.size > 4) artworkCache.remove(artworkCache.keys.first())
        if (nowPlaying.artworkTransferId == id) {
            artwork = decoded
            session?.setMetadata(NowPlayingMetadata.androidMetadata(nowPlaying, artwork))
        }
    }

    private fun publish() {
        val playing = phonePlaying ?: audioActive
        if (session == null && (connected || audioActive || playing)) {
            session = MediaSession(context, "L7CarPlay").apply {
                setCallback(callback, handler)
                setMetadata(NowPlayingMetadata.androidMetadata(nowPlaying, artwork))
                isActive = true
            }
        }
        session?.setPlaybackState(PlaybackState.Builder().setActions(ACTIONS)
            .setState(if (playing) PlaybackState.STATE_PLAYING else if (phonePlaying != null || audioActive) PlaybackState.STATE_PAUSED else PlaybackState.STATE_NONE,
                nowPlaying.elapsedMillis ?: PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                if (playing) 1f else 0f, elapsedUpdatedAt).build())
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
        // 保留队列已安排的交付任务，让迟到图片经过代次检查后释放。
        artworkOwner = null
        artworkQueue.clear()
        artworkCache.clear()
        artwork = null
        session?.let { it.isActive = false; it.release() }
        session = null
    }

    private companion object {
        val artworkWorker = Executors.newSingleThreadExecutor { task ->
            Thread(task, "l7-now-playing-artwork").apply { isDaemon = true }
        }
        val MEDIA_KEYS = setOf(KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK)
        const val ACTIONS = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
    }
}
