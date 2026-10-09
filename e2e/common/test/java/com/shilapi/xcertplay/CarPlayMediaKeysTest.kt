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
import org.junit.Before
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
class GalaxyMediaKeysTest {
    @Before fun resetProviderPathCache() {
        org.robolectric.util.ReflectionHelpers.getStaticField<MutableMap<String, Any>>(
            androidx.core.content.FileProvider::class.java, "sCache").clear()
    }
    @Test fun retiredHostContextIsNotUsedByControllerListeners() {
        val app = RuntimeEnvironment.getApplication()
        var retired = false
        val context = object : ContextWrapper(app) {
            override fun getResources(): Resources { check(!retired); return super.getResources() }
            override fun getPackageManager(): android.content.pm.PackageManager { check(!retired); return super.getPackageManager() }
            override fun getSystemService(name: String): Any? { check(!retired); return super.getSystemService(name) }
        }
        val controller = mock(CarPlayController::class.java)
        var connected: ((Boolean) -> Unit)? = null
        doAnswer { connected = it.getArgument(0); null }.`when`(controller).sessionStateListener = any()
        GalaxyMediaKeys.attach(context, controller, {})
        try {
            retired = true
            `when`(controller.hasActiveSession()).thenReturn(true)
            connected!!(true)
            assertSame(controller, ReflectionHelpers.getField(GalaxyMediaKeys, "controller"))
        } finally { GalaxyMediaKeys.detach(controller) }
    }

    @Test fun oldMetadataAndDetachCannotAffectReplacementController() {
        val app = RuntimeEnvironment.getApplication()
        val old = mock(CarPlayController::class.java)
        val next = mock(CarPlayController::class.java)
        var metadata: ((CarPlayNowPlaying) -> Unit)? = null
        doAnswer { metadata = it.getArgument(0); null }.`when`(old).nowPlayingListener = any()
        GalaxyMediaKeys.attach(app, old, {})
        val oldMetadata = metadata!!
        GalaxyMediaKeys.attach(app, next, {})
        try {
            oldMetadata(CarPlayNowPlaying(title = "retired session"))
            GalaxyMediaKeys.detach(old)
            assertSame(next, ReflectionHelpers.getField(GalaxyMediaKeys, "controller"))
            assertNull(ReflectionHelpers.getField<CarPlayNowPlaying>(GalaxyMediaKeys, "mediaInfo").title)
        } finally { GalaxyMediaKeys.detach(next) }
    }

    @Test fun metadataListenerPublishesOneSnapshotWithSelectedCoverRatherThanPreviousUri() {
        val app = RuntimeEnvironment.getApplication()
        val controller = mock(CarPlayController::class.java)
        var metadata: ((CarPlayNowPlaying) -> Unit)? = null
        doAnswer { metadata = it.getArgument(0); null }.`when`(controller).nowPlayingListener = any()
        val center = mock(L7MediaCenterSession::class.java)
        val direct = java.util.concurrent.Executor { it.run() }
        GalaxyMediaKeys.attach(app, controller, {})
        val covers = L7MediaArtwork(app, direct, direct) { uri ->
            val value: CarPlayNowPlaying = ReflectionHelpers.getField(GalaxyMediaKeys, "mediaInfo")
            center.update(value, uri)
        }
        ReflectionHelpers.setField(GalaxyMediaKeys, "covers", covers)
        ReflectionHelpers.setField(GalaxyMediaKeys, "mediaCenter", center)
        try {
            val bytes = java.io.ByteArrayOutputStream().also { output ->
                android.graphics.Bitmap.createBitmap(10, 10, android.graphics.Bitmap.Config.ARGB_8888).let {
                    it.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output); it.recycle()
                }
            }.toByteArray()
            covers.submit(1, bytes)
            val first = CarPlayNowPlaying(title = "first", artworkTransferId = 1, playbackKnown = true)
            metadata!!(first)
            val firstCalls = mockingDetails(center).invocations.filter { it.method.name == "update" }
            assertEquals(1, firstCalls.size)
            assertEquals(first, firstCalls.single().getArgument<CarPlayNowPlaying>(0))
            assertNotNull(firstCalls.single().getArgument<android.net.Uri?>(1))
            clearInvocations(center)
            val next = first.copy(title = "second", artworkTransferId = 2)
            metadata!!(next)
            val nextCalls = mockingDetails(center).invocations.filter { it.method.name == "update" }
            assertEquals(1, nextCalls.size)
            assertEquals(next, nextCalls.single().getArgument<CarPlayNowPlaying>(0))
            assertNull(nextCalls.single().getArgument<android.net.Uri?>(1))
        } finally { GalaxyMediaKeys.detach(controller) }
    }

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
        // 本用例验证本地音乐模式的媒体会话；产品 USB 与无线均固定使用此模式。
        `when`(controller.localMediaAudioEnabled).thenReturn(true)
        var resumed = 0
        val construction = mockConstruction(MediaSession::class.java)
        GalaxyMediaKeys.attach(context, controller, { resumed++ })
        val bridge: CarPlayMediaSession = ReflectionHelpers.getField(GalaxyMediaKeys, "bridge")
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
        } finally { GalaxyMediaKeys.detach(controller); construction.close() }
    }
    @Test fun stockBluetoothMetadataDoesNotCreateLocalSessionOrResumePhone() {
        val app = RuntimeEnvironment.getApplication()
        val controller = mock(CarPlayController::class.java)
        `when`(controller.localMediaAudioEnabled).thenReturn(false)
        `when`(controller.hasActiveSession()).thenReturn(true)
        `when`(controller.navigationSnapshot()).thenReturn(com.shilapi.xcertplay.hud.CarPlayNavigationSnapshot())
        var metadata: ((CarPlayNowPlaying) -> Unit)? = null
        doAnswer { metadata = it.getArgument(0); null }.`when`(controller).nowPlayingListener = any()
        var resumed = 0
        mockConstruction(MediaSession::class.java).use { construction ->
            GalaxyMediaKeys.attach(app, controller, { resumed++ })
            try {
                metadata!!(CarPlayNowPlaying(title = "stock song", playing = true, playbackKnown = true))
                shadowOf(Looper.getMainLooper()).idle()
                assertNull(ReflectionHelpers.getField<Any?>(GalaxyMediaKeys, "bridge"))
                assertEquals(0, construction.constructed().size)
                assertEquals(0, resumed)
                assertEquals("stock song", ReflectionHelpers.getField<CarPlayNowPlaying>(GalaxyMediaKeys, "mediaInfo").title)
            } finally { GalaxyMediaKeys.detach(controller) }
        }
    }
}
