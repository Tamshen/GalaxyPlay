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
    private var steeringWheel: L7SteeringWheel? = null

    @Synchronized fun attach(context: Context, next: CarPlayController, onPlaybackStarted: () -> Unit,
                             assistantActive: () -> Boolean = { false }) {
        if (controller === next) return
        controller?.playbackListener = null
        bridge?.close()
        steeringWheel?.close()
        controller = next
        bridge = CarPlayMediaSession(context.applicationContext, onPlaybackStarted) { index, source ->
            val action = {
                val current = synchronized(this) { controller?.takeIf { it === next } }
                if (current != null) {
                    if (index == CarPlayMediaButton.PLAY) onPlaybackStarted()
                    val sent = CarPlayVideo.onMediaKey(index) || current.sendMediaButton(index)
                    L7DebugLog.record("Audio: media command source=$source index=$index sent=$sent")
                }
            }
            CarPlayBackgroundSession.beforeMediaCommand(next, index, action)
        }
        next.playbackListener = { playing ->
            synchronized(this) { if (controller === next) bridge?.onIphonePlaying(playing) }
        }
        if (context.resources.getBoolean(com.shilapi.xcertplay.host.R.bool.config_l7_product_ui)) {
            steeringWheel = L7SteeringWheel(context.applicationContext, next::hasActiveSession, assistantActive) {
                synchronized(this) { controller === next && next.requestSiri() }
            }.also { it.start() }
        }
    }

    @Synchronized fun detach(expected: CarPlayController?) {
        if (expected == null || controller !== expected) return
        expected.playbackListener = null
        bridge?.close()
        steeringWheel?.close()
        steeringWheel = null
        bridge = null
        controller = null
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
        val index = if (explicitHardwareActions && event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY) CarPlayMediaButton.PLAY
            else if (explicitHardwareActions && event.keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE) CarPlayMediaButton.PAUSE
            else CarPlayMediaButton.forKeyCode(event.keyCode) ?: return super.onMediaButtonEvent(mediaButtonIntent)
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            send(index, KeyEvent.keyCodeToString(event.keyCode))
        }
        return true
    }

    override fun onPlay() = send(CarPlayMediaButton.PLAY, "controller-play")
    override fun onPause() = send(CarPlayMediaButton.PAUSE, "controller-pause")
    override fun onSkipToNext() = send(CarPlayMediaButton.NEXT, "next")
    override fun onSkipToPrevious() = send(CarPlayMediaButton.PREVIOUS, "previous")
}
