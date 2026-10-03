package com.shilapi.xcertplay

import android.content.Context
import android.media.AudioManager
import android.media.session.PlaybackState
import android.view.KeyEvent
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class CarPlayMediaSessionTest {
    private val context = RuntimeEnvironment.getApplication()
    private var resumes = 0
    private val bridge = CarPlayMediaSession(context, { resumes++ }) { _, _ -> }

    @Test fun keysNeverCreateTheirOwnAudioFocusAndPhonePauseWinsOverOpenStream() {
        try {
            bridge.onMediaAudioChanged(true); idle()
            assertEquals(PlaybackState.STATE_PLAYING, publishedState().state)
            bridge.onIphonePlaying(false); idle()
            bridge.onMediaAudioChanged(true); idle()
            assertEquals(PlaybackState.STATE_PAUSED, publishedState().state)
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            assertNull(shadowOf(audio).lastAudioFocusRequest)
            assertEquals(0, resumes)
        } finally { bridge.close() }
    }

    @Test fun onlyNewPlayingStateRequestsResumeAndClosedQueueCannotReviveSession() {
        bridge.onIphonePlaying(true); idle()
        bridge.onIphonePlaying(true); idle()
        assertEquals(1, resumes)
        bridge.onIphonePlaying(false); idle()
        bridge.onIphonePlaying(true)
        bridge.close(); idle()
        assertNull(bridge.session)
        assertEquals(1, resumes)
    }

    @Test fun toggleUsesPhoneStateForExplicitPlayAndPause() {
        val sent = mutableListOf<Int>()
        val session = CarPlayMediaSession(context, {}) { index, _ -> sent += index }
        try {
            val key = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            session.onIphonePlaying(false); idle()
            session.onHardwareKey(key)
            session.onIphonePlaying(true); idle()
            session.onHardwareKey(key)
            assertEquals(listOf(CarPlayMediaButton.PLAY, CarPlayMediaButton.PAUSE), sent)
        } finally { session.close() }
    }

    @Test fun focusedHardwareKeysSendOnceAndUnsupportedVehicleKeysAreIgnored() {
        val sent = mutableListOf<Int>()
        val session = CarPlayMediaSession(context, {}) { index, _ -> sent += index }
        try {
            assertTrue(session.onHardwareKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT)))
            assertTrue(session.onHardwareKey(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT)))
            assertFalse(session.onHardwareKey(KeyEvent(KeyEvent.ACTION_DOWN, 353)))
            assertEquals(listOf(CarPlayMediaButton.NEXT), sent)
            session.close()
            assertFalse(session.onHardwareKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY)))
        } finally { session.close() }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    // Robolectric 未提供完整的媒体服务 Binder；读取框架 API 实际保存的状态，避免用空的 controller 回读。
    private fun publishedState(): PlaybackState = ReflectionHelpers.getField(bridge.session!!, "mPlaybackState")
}
