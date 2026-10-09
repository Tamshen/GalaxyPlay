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
    @Test fun oldBluetoothMusicPreferencesCannotDisableLocalMusic() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        assertTrue(GalaxyMusicPlayback.localEnabled(app))
        prefs.edit().putBoolean("galaxy_local_music_enabled", false)
            .putBoolean("bluetooth_media_exclusive", false).commit()
        assertTrue(GalaxyMusicPlayback.localEnabled(app))
        assertFalse(prefs.getBoolean("bluetooth_media_exclusive", true))
    }
}
