package com.shilapi.xcertplay

import android.content.Context
import android.media.session.MediaSession
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.mockingDetails
import android.media.MediaMetadata
import com.shilapi.xcertplay.media.CarPlayNowPlaying
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

    @Test fun connectionEnablesControlBeforeAudioAndDisconnectRejectsLatePhoneState() {
        bridge.onConnected(true); idle()
        assertNotNull(bridge.session)
        assertEquals(PlaybackState.STATE_NONE, publishedState().state)
        assertNull(shadowOf(context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).lastAudioFocusRequest)
        bridge.onConnected(false); idle()
        bridge.onIphonePlaying(true); bridge.onMediaAudioChanged(true); idle()
        assertNull(bridge.session)
        assertEquals(0, resumes)
        bridge.close()
    }

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

    @Test fun metadataUpdatesPreservePhonePauseAndDoNotRequestFocus() {
        // Robolectric 不实现元数据 Binder，捕获框架发布调用及次数。
        val construction = mockConstruction(MediaSession::class.java)
        try {
            bridge.onMediaAudioChanged(true); idle()
            val song = CarPlayNowPlaying(title = "Song", artist = "Artist", durationMillis = 60_000,
                elapsedMillis = 1_000, playing = false, playbackKnown = true)
            bridge.onNowPlayingChanged(song); idle()
            fun metadataCalls() = mockingDetails(bridge.session!!).invocations.filter { it.method.name == "setMetadata" }
            fun state() = mockingDetails(bridge.session!!).invocations.last { it.method.name == "setPlaybackState" }
                .arguments[0] as PlaybackState
            val first = metadataCalls().last().arguments[0] as MediaMetadata
            val count = metadataCalls().size
            assertEquals("Song", first.getString(MediaMetadata.METADATA_KEY_TITLE))
            assertEquals(PlaybackState.STATE_PAUSED, state().state)
            bridge.onNowPlayingChanged(song.copy(elapsedMillis = 2_000)); idle()
            assertEquals("进度更新不能重发歌曲信息及封面", count, metadataCalls().size)
            assertEquals(2_000L, state().position)
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            assertNull(shadowOf(audio).lastAudioFocusRequest)
            assertEquals(0, resumes)
        } finally { bridge.close(); construction.close() }
    }

    @Test fun firstPlayingMetadataResumesOnceAndLateMetadataCannotReviveClosedSession() {
        val song = CarPlayNowPlaying(title = "Song", playing = true, playbackKnown = true)
        bridge.onNowPlayingChanged(song); idle()
        bridge.onIphonePlaying(true); idle()
        assertEquals(1, resumes)
        bridge.onNowPlayingChanged(song.copy(title = "Next"))
        bridge.close(); idle()
        assertNull(bridge.session)
        assertEquals(1, resumes)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    // Robolectric 未提供完整的媒体服务 Binder；读取框架 API 实际保存的状态，避免用空的 controller 回读。
    private fun publishedState(): PlaybackState = ReflectionHelpers.getField(bridge.session!!, "mPlaybackState")
}
