package com.shilapi.xcertplay

import android.content.ContextWrapper
import android.content.res.Resources
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.media.session.MediaSession
import android.os.Looper
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class CarPlayMediaKeysTest {
    @Test fun metadataBeforeAirPlayActivationIsHeldUntilRealSessionThenReplayed() {
        val app = RuntimeEnvironment.getApplication()
        val resources = spy(app.resources)
        `when`(resources.getBoolean(com.shilapi.xcertplay.host.R.bool.config_l7_product_ui)).thenReturn(true)
        val context = object : ContextWrapper(app) { override fun getResources(): Resources = resources }
        val controller = mock(CarPlayController::class.java)
        var active = false
        var connection: ((Boolean) -> Unit)? = null
        var metadata: ((CarPlayNowPlaying) -> Unit)? = null
        doAnswer { connection = it.getArgument(0); null }.`when`(controller).sessionStateListener = any()
        doAnswer { metadata = it.getArgument(0); null }.`when`(controller).nowPlayingListener = any()
        `when`(controller.hasActiveSession()).thenAnswer { active }
        `when`(controller.navigationSnapshot()).thenReturn(com.shilapi.xcertplay.hud.CarPlayNavigationSnapshot())
        `when`(controller.activeMediaSessionOwner()).thenReturn(Any())
        var resumed = 0
        val construction = mockConstruction(MediaSession::class.java)
        CarPlayMediaKeys.attach(context, controller, { resumed++ })
        val bridge: CarPlayMediaSession = ReflectionHelpers.getField(CarPlayMediaKeys, "bridge")
        try {
            shadowOf(Looper.getMainLooper()).idle()
            metadata!!(CarPlayNowPlaying(title = "synthetic-song", playing = true, playbackKnown = true))
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(bridge.session)
            assertEquals(0, resumed)
            active = true
            connection!!(true)
            shadowOf(Looper.getMainLooper()).idle()
            val song: MediaMetadata = mockingDetails(bridge.session!!).invocations
                .last { it.method.name == "setMetadata" }.getArgument(0)
            val state: PlaybackState = mockingDetails(bridge.session!!).invocations
                .last { it.method.name == "setPlaybackState" }.getArgument(0)
            assertEquals("synthetic-song", song.getString(MediaMetadata.METADATA_KEY_TITLE))
            assertEquals(PlaybackState.STATE_PLAYING, state.state)
            assertEquals(1, resumed)
            active = false
            connection!!(false)
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(bridge.session)
        } finally { CarPlayMediaKeys.detach(controller); construction.close() }
    }
}
