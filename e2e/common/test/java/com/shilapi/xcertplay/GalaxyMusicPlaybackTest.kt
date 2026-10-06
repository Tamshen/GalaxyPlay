package com.shilapi.xcertplay

import android.content.Context
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30], manifest = Config.NONE)
class GalaxyMusicPlaybackTest {
    @Test fun oldExclusivePreferenceCannotEnableLocalMusicAndSnapshotIsFrozen() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().putBoolean("bluetooth_media_exclusive", true).commit()
        val sessionLocal = GalaxyMusicPlayback.localEnabled(app)
        assertFalse(sessionLocal)
        GalaxyMusicPlayback.saveLocalEnabled(app, true)
        assertFalse(sessionLocal)
        assertTrue(GalaxyMusicPlayback.localEnabled(app))
        GalaxyMusicPlayback.saveLocalEnabled(app, false)
        assertFalse(GalaxyMusicPlayback.localEnabled(app))
    }
}
