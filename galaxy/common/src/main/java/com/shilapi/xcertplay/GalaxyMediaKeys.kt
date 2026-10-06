package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.view.KeyEvent
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.orchestration.CarPlayController

/** 媒体键只维护会话和转发命令，系统焦点统一由实际播放模块持有。 */
internal object GalaxyMediaKeys {
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
        val context = context.applicationContext
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
        L7SteeringDiagnostics.store.connection(false)
        controller = next
        mediaInfo = com.shilapi.xcertplay.media.CarPlayNowPlaying()
        pendingArtwork = null
        commandGate = L7MediaCommandGate()
        val commands = dispatcher(next, onPlaybackStarted)
        val dispatch: (Int, String) -> Unit = { index, source -> commands.dispatch(index, source, L7SteeringDiagnostics.begin(source, index)) }
        val traced: (Int, String, L7SteeringTrace) -> Unit = commands::dispatch
        bridge = if (next.localMediaAudioEnabled) CarPlayMediaSession(context.applicationContext, onPlaybackStarted, dispatch, traced) else null
        next.sessionStateListener = { active -> onConnection(context, next, active, dispatch, traced) }
        next.navigationListener = { value -> synchronized(this) { if (controller === next) navigation?.update(value) } }
        next.playbackListener = { playing ->
            synchronized(this) { if (controller === next) { L7SteeringDiagnostics.store.state("phonePlayback", "playing=$playing"); bridge?.onIphonePlaying(playing) } }
        }
        next.nowPlayingListener = { update ->
            synchronized(this) {
                if (controller === next) {
                    if (update.playbackKnown) L7SteeringDiagnostics.store.state("phonePlayback", "playing=${update.playing}")
                    bridge?.onNowPlayingChanged(update)
                    mediaInfo = update
                    if (update == com.shilapi.xcertplay.media.CarPlayNowPlaying()) {
                        pendingArtwork = null
                        covers?.reset()
                    }
                    // 选定封面后只发布一次快照，后台不能读到新曲目与旧 URI 的组合。
                    covers?.select(update.artworkTransferId) ?: mediaCenter?.update(update, null)
                }
            }
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
        onConnection(context, next, next.hasActiveSession(), dispatch, traced)
    }

    private fun dispatcher(next: CarPlayController, onPlaybackStarted: () -> Unit): L7MediaCommandDispatcher =
        L7MediaCommandDispatcher(next::activeMediaSessionOwner,
            { synchronized(this) { controller === next } },
            { index, source -> synchronized(this) { commandGate.accept(index, source) } },
            { index, action -> CarPlayBackgroundSession.beforeMediaCommand(next, index, action) },
            CarPlayVideo::onMediaKey, { index, owner -> next.sendMediaButton(index, owner) }, onPlaybackStarted,
            traceSend = { index, owner, trace -> next.sendMediaButton(index, owner, trace::step) },
            traceBefore = { index, action, dropped -> CarPlayBackgroundSession.beforeMediaCommand(next, index, action, dropped) })

    @Synchronized private fun onConnection(context: Context, next: CarPlayController, active: Boolean,
                                          dispatch: (Int, String) -> Unit, traced: (Int, String, L7SteeringTrace) -> Unit) {
        if (controller !== next) return
        L7SteeringDiagnostics.store.connection(active)
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
                override fun run() = synchronized(this@GalaxyMediaKeys) {
                    if (controller === next && next.hasActiveSession() && navigationTick === this) {
                        navigation?.update(next.navigationSnapshot())
                        handler.postDelayed(this, 1000)
                    }
                }
            }.also { handler.post(it) }
            lateinit var coverOwner: L7MediaArtwork
            coverOwner = L7MediaArtwork(context) { _ -> synchronized(this) {
                // 解码通知等待宿主锁期间可能已经切歌，重新取当前选择，不能复用通知里的旧 URI。
                if (controller === next && covers === coverOwner)
                    mediaCenter?.update(mediaInfo, coverOwner.selectedUri(mediaInfo.artworkTransferId))
            } }
            covers = coverOwner
            pendingArtwork?.let { (id, bytes) -> coverOwner.submit(id, bytes) }
            pendingArtwork = null
            mediaCenter = L7MediaCenterSession(L7ReflectiveMediaCenter(context.applicationContext), context.packageName,
                { synchronized(this) { controller === next && next.hasActiveSession() } }, dispatch, traceSend = traced, localPlayback = next.localMediaAudioEnabled).also { it.start(); it.update(mediaInfo) }
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
        L7SteeringDiagnostics.store.connection(false)
    }

    private fun stopNavigation() {
        navigationTick?.let(handler::removeCallbacks)
        navigationTick = null
        navigation?.close()
        navigation = null
    }

    @Synchronized fun onMediaAudioChanged(expected: CarPlayController, active: Boolean) {
        if (controller === expected) { L7SteeringDiagnostics.store.state("audioStream", "active=$active"); bridge?.onMediaAudioChanged(active) }
    }

    @Synchronized fun onHardwareKey(expected: CarPlayController?, event: KeyEvent): Boolean {
        VehicleSteeringInputLog.key(event, "vehicle-window-key")
        if (CarPlayMediaButton.forKeyCode(event.keyCode) == null) return false
        if (expected == null || controller !== expected || !expected.hasActiveSession()) {
            L7SteeringDiagnostics.begin("window-key", -1, "code=${event.keyCode} action=${event.action} repeat=${event.repeatCount}")
                .step("DROP", if (expected == null || !expected.hasActiveSession()) "NO_SESSION" else "STALE_CONTROLLER")
            return false
        }
        return bridge?.onHardwareKey(event) == true
    }

    @Synchronized fun onVoiceKey(expected: CarPlayController?): Boolean =
        expected != null && controller === expected && steeringWheel?.onVoiceKey() == true
}

/** 系统媒体控制用明确播放/暂停，硬件键保持上游切换语义；长按重复事件不转发。 */
internal class GalaxyMediaCallback(
    private val explicitHardwareActions: Boolean = false,
    private val tracedSend: ((Int, String, L7SteeringTrace) -> Unit)? = null,
    private val observeKey: ((KeyEvent, String) -> Unit)? = null,
    private val send: (index: Int, source: String) -> Unit,
) : MediaSession.Callback() {
    override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
        @Suppress("DEPRECATION")
        val event = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
        return onKey(event, "media-session-key")
    }

    fun onKey(event: KeyEvent, source: String): Boolean {
        observeKey?.invoke(event, "vehicle-$source")
        L7DebugLog.record("Audio: media key source=$source action=${event.action} repeat=${event.repeatCount} code=${event.keyCode}")
        val index = if (explicitHardwareActions && event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY) CarPlayMediaButton.PLAY
            else if (explicitHardwareActions && event.keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE) CarPlayMediaButton.PAUSE
            else CarPlayMediaButton.forKeyCode(event.keyCode) ?: run {
                if (tracedSend != null) L7SteeringDiagnostics.begin(source, -1, "code=${event.keyCode} action=${event.action}").step("DROP", "UNSUPPORTED_KEY")
                return false
            }
        val origin = "$source:${KeyEvent.keyCodeToString(event.keyCode)}"
        val trace = if (tracedSend != null) L7SteeringDiagnostics.begin(origin, index,
            "code=${event.keyCode} action=${event.action} repeat=${event.repeatCount}") else null
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            if (trace != null) tracedSend?.invoke(index, origin, trace) else send(index, origin)
        } else trace?.step("FILTER", if (event.repeatCount != 0) "REPEAT" else "KEY_UP_OR_OTHER_ACTION")
        return true
    }

    private fun command(index: Int, source: String) {
        if (tracedSend != null) tracedSend.invoke(index, source, L7SteeringDiagnostics.begin(source, index)) else send(index, source)
    }

    override fun onPlay() = command(CarPlayMediaButton.PLAY, "controller-play")
    override fun onPause() = command(CarPlayMediaButton.PAUSE, "controller-pause")
    override fun onSkipToNext() = command(CarPlayMediaButton.NEXT, "next")
    override fun onSkipToPrevious() = command(CarPlayMediaButton.PREVIOUS, "previous")
}
