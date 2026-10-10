package com.shilapi.xcertplay

import android.app.PendingIntent
import android.net.Uri
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30])
class GalaxyOemPlaybackSnapshotTest {
    class Getters {
        fun getTitle(): String? = null
        fun getDuration(): Long = 0
        fun getPlaybackStatus(): Int = 0
        fun getAppIcon(): String? = null
        fun getPackageName(): String? = null
        fun getLaunchIntent(): PendingIntent? = null
        fun getPlayerIntent(): PendingIntent? = null
        fun getArtwork(): Uri? = null
        fun getUuid(): String? = null
        fun getVip(): Int = -1
        fun getPlayingMediaListType(): Int = 0
        fun isSupportCollect(): Boolean = true
        fun isSupportDownload(): Boolean = true
        fun isSupportLoopModeSwitch(): Boolean = true
        fun isSupportVrCtrlPlayStatus(): Boolean = true
    }
    private val app = RuntimeEnvironment.getApplication()
    private var active = true
    private var cover = Uri.parse("content://synthetic/current")
    private val value = CarPlayNowPlaying(title = "synthetic", durationMillis = 180123, playing = true, playbackKnown = true)
    private fun snapshot(control: Boolean = true) = GalaxyOemPlaybackSnapshot(app, value, 6, "track-one",
        { active }, { cover }, control)
    private fun get(snapshot: GalaxyOemPlaybackSnapshot, name: String) = snapshot.invoke(null, Getters::class.java.getMethod(name), null)

    @Test fun cardHasOwnIconAndImmutableReturnIntentAndOriginalMilliseconds() {
        app.applicationInfo.icon = 123
        val snapshot = snapshot()
        assertEquals("android.resource://${app.packageName}/123", get(snapshot, "getAppIcon"))
        assertEquals(app.packageName, get(snapshot, "getPackageName"))
        val intent = get(snapshot, "getLaunchIntent") as PendingIntent
        assertEquals(intent, get(snapshot, "getPlayerIntent"))
        assertEquals(GalaxySettingsActivity::class.java.name, shadowOf(intent).savedIntent.component?.className)
        assertEquals("home", shadowOf(intent).savedIntent.getStringExtra("page"))
        assertTrue(shadowOf(intent).flags and PendingIntent.FLAG_IMMUTABLE != 0)
        assertEquals(180123L, get(snapshot, "getDuration"))
        assertEquals(1, get(snapshot, "getPlaybackStatus"))
        assertEquals(cover, get(snapshot, "getArtwork"))
    }
    @Test fun unprovidedPhoneCapabilitiesNeverInheritOemSubscriptionOrPlaylistDefaults() {
        val snapshot = snapshot(false)
        for (name in listOf("isSupportCollect", "isSupportDownload", "isSupportLoopModeSwitch", "isSupportVrCtrlPlayStatus"))
            assertEquals(false, get(snapshot, name))
        assertEquals(0, get(snapshot, "getVip"))
        assertEquals(-1, get(snapshot, "getPlayingMediaListType"))
        assertEquals("track-one", get(snapshot, "getUuid"))
        assertEquals(true, get(snapshot(), "isSupportVrCtrlPlayStatus"))
    }
    @Test fun invalidatedSnapshotCannotExposeArtworkOrReturnIntentOrControlCapabilities() {
        val snapshot = snapshot(); active = false
        for (name in listOf("getTitle", "getArtwork", "getLaunchIntent", "getPlayerIntent", "getAppIcon")) assertNull(get(snapshot, name))
        assertEquals(0L, get(snapshot, "getDuration"))
        assertEquals(false, get(snapshot, "isSupportVrCtrlPlayStatus"))
    }
}
