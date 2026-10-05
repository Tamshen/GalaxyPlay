package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.view.KeyEvent
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.orchestration.CarPlayController

/** 媒体键只维护会话和转发命令，系统焦点统一由实际播放模块持有。 */
internal object CarPlayMediaKeys {
    private var controller: CarPlayController? = null
    private var bridge: CarPlayMediaSession? = null
    private var commandGate = L7MediaCommandGate()
    private var mediaCenter: L7MediaCenterSession? = null
    private var navigation: L7NavigationSession? = null
    private var navigationTick: Runnable? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var covers: L7MediaArtwork? = null
    private var pendingArtwork: Pair<Int, ByteArray>? = null
    private var mediaInfo = com.shilapi.xcertplay.media.CarPlayNowPlaying()
    private var steeringWheel: L7SteeringWheel? = null

    @Synchronized fun attach(context: Context, next: CarPlayController, onPlaybackStarted: () -> Unit,
                             assistantActive: () -> Boolean = { false }) {
        if (controller === next) return
        controller?.sessionStateListener = null
        controller?.navigationListener = null
        stopNavigation()
        controller?.playbackListener = null
        controller?.nowPlayingListener = null
        controller?.artworkListener = null
        bridge?.close()
        mediaCenter?.close()
        covers?.close()
        covers = null
        mediaCenter = null
        steeringWheel?.close()
        controller = next
        mediaInfo = com.shilapi.xcertplay.media.CarPlayNowPlaying()
        pendingArtwork = null
        commandGate = L7MediaCommandGate()
        val dispatch = dispatcher(next, onPlaybackStarted)
        bridge = CarPlayMediaSession(context.applicationContext, onPlaybackStarted, dispatch)
        next.sessionStateListener = { active -> onConnection(context, next, active, dispatch) }
        next.navigationListener = { value -> synchronized(this) { if (controller === next) navigation?.update(value) } }
        next.playbackListener = { playing ->
            synchronized(this) { if (controller === next) bridge?.onIphonePlaying(playing) }
        }
        next.nowPlayingListener = { update ->
            synchronized(this) { if (controller === next) { bridge?.onNowPlayingChanged(update); mediaInfo = update; if (update == com.shilapi.xcertplay.media.CarPlayNowPlaying()) pendingArtwork = null; mediaCenter?.update(update); covers?.select(update.artworkTransferId) } }
        }
        next.artworkListener = { id, bytes ->
            synchronized(this) { if (controller === next) {
                bridge?.onArtworkChanged(id, bytes)
                if (covers != null) covers?.submit(id, bytes)
                else if (!next.hasActiveSession() && context.resources.getBoolean(com.shilapi.xcertplay.host.R.bool.config_l7_product_ui) && id in 0..255 && bytes.size <= com.shilapi.xcertplay.transport.Iap2FileTransferReceiver.DEFAULT_MAXIMUM_ARTWORK_BYTES)
                    pendingArtwork = id to bytes
            } }
        }
        if (context.resources.getBoolean(com.shilapi.xcertplay.host.R.bool.config_l7_product_ui)) {
            steeringWheel = L7SteeringWheel(context.applicationContext, next::hasActiveSession, assistantActive) {
                synchronized(this) { controller === next && next.requestSiri() }
            }.also { it.start() }
        }
        onConnection(context, next, next.hasActiveSession(), dispatch)
    }

    private fun dispatcher(next: CarPlayController, onPlaybackStarted: () -> Unit): (Int, String) -> Unit =
        L7MediaCommandDispatcher(next::activeMediaSessionOwner,
            { synchronized(this) { controller === next } },
            { index, source -> synchronized(this) { commandGate.accept(index, source) } },
            { index, action -> CarPlayBackgroundSession.beforeMediaCommand(next, index, action) },
            CarPlayVideo::onMediaKey, { index, owner -> next.sendMediaButton(index, owner) }, onPlaybackStarted)::dispatch

    @Synchronized private fun onConnection(context: Context, next: CarPlayController, active: Boolean,
                                          dispatch: (Int, String) -> Unit) {
        if (controller !== next) return
        bridge?.onConnected(active)
        if (!active) {
            commandGate = L7MediaCommandGate()
            stopNavigation()
            mediaCenter?.close()
            covers?.close()
            covers = null
            mediaCenter = null
            mediaInfo = com.shilapi.xcertplay.media.CarPlayNowPlaying()
            pendingArtwork = null
        } else if (mediaCenter == null && context.resources.getBoolean(com.shilapi.xcertplay.host.R.bool.config_l7_product_ui)) {
            navigation = L7NavigationSession(L7ReflectiveNavigation(context), {
                synchronized(this) { controller === next && next.hasActiveSession() }
            })
            navigationTick = object : Runnable {
                override fun run() = synchronized(this@CarPlayMediaKeys) {
                    if (controller === next && next.hasActiveSession() && navigationTick === this) {
                        navigation?.update(next.navigationSnapshot())
                        handler.postDelayed(this, 1000)
                    }
                }
            }.also { handler.post(it) }
            lateinit var coverOwner: L7MediaArtwork
            coverOwner = L7MediaArtwork(context) { uri -> synchronized(this) {
                if (controller === next && covers === coverOwner) mediaCenter?.update(mediaInfo, uri)
            } }
            covers = coverOwner
            pendingArtwork?.let { (id, bytes) -> coverOwner.submit(id, bytes) }
            pendingArtwork = null
            mediaCenter = L7MediaCenterSession(L7ReflectiveMediaCenter(context.applicationContext), context.packageName,
                { synchronized(this) { controller === next && next.hasActiveSession() } }, dispatch).also { it.start(); it.update(mediaInfo) }
            coverOwner.select(mediaInfo.artworkTransferId)
        }
        if (active) bridge?.onNowPlayingChanged(mediaInfo)
    }

    @Synchronized fun detach(expected: CarPlayController?) {
        if (expected == null || controller !== expected) return
        expected.sessionStateListener = null
        expected.navigationListener = null
        stopNavigation()
        expected.playbackListener = null
        expected.nowPlayingListener = null
        expected.artworkListener = null
        bridge?.close()
        mediaCenter?.close()
        covers?.close()
        covers = null
        mediaCenter = null
        steeringWheel?.close()
        steeringWheel = null
        bridge = null
        pendingArtwork = null
        mediaInfo = com.shilapi.xcertplay.media.CarPlayNowPlaying()
        controller = null
    }

    private fun stopNavigation() {
        navigationTick?.let(handler::removeCallbacks)
        navigationTick = null
        navigation?.close()
        navigation = null
    }

    @Synchronized fun onMediaAudioChanged(expected: CarPlayController, active: Boolean) {
        if (controller === expected) bridge?.onMediaAudioChanged(active)
    }

    @Synchronized fun onHardwareKey(expected: CarPlayController?, event: KeyEvent): Boolean =
        expected != null && controller === expected && expected.hasActiveSession() && bridge?.onHardwareKey(event) == true

    @Synchronized fun onVoiceKey(expected: CarPlayController?): Boolean =
        expected != null && controller === expected && steeringWheel?.onVoiceKey() == true
}

/** 系统媒体控制用明确播放/暂停，硬件键保持上游切换语义；长按重复事件不转发。 */
internal class CarPlayMediaCallback(
    private val explicitHardwareActions: Boolean = false,
    private val send: (index: Int, source: String) -> Unit,
) : MediaSession.Callback() {
    override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
        @Suppress("DEPRECATION")
        val event = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
        return onKey(event, "media-session-key")
    }

    fun onKey(event: KeyEvent, source: String): Boolean {
        L7DebugLog.record("Audio: media key source=$source action=${event.action} repeat=${event.repeatCount} code=${event.keyCode}")
        val index = if (explicitHardwareActions && event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY) CarPlayMediaButton.PLAY
            else if (explicitHardwareActions && event.keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE) CarPlayMediaButton.PAUSE
            else CarPlayMediaButton.forKeyCode(event.keyCode) ?: return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            send(index, "$source:${KeyEvent.keyCodeToString(event.keyCode)}")
        }
        return true
    }

    override fun onPlay() = send(CarPlayMediaButton.PLAY, "controller-play")
    override fun onPause() = send(CarPlayMediaButton.PAUSE, "controller-pause")
    override fun onSkipToNext() = send(CarPlayMediaButton.NEXT, "next")
    override fun onSkipToPrevious() = send(CarPlayMediaButton.PREVIOUS, "previous")
}
