package com.shilapi.xcertplay

import com.shilapi.xcertplay.host.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30], manifest = Config.NONE)
class GalaxyPerformanceFpsLabelsTest {
    @Test fun noProjectionWaitingAndAppIdleAreExplicitInBothViews() {
        val app = RuntimeEnvironment.getApplication()
        val input = GalaxyPerformanceInput(0, 0, 0, 0, null, null, null, null, null, null, emptyList(), emptyList())
        val sample = GalaxyPerformanceSample(input, 1.0, emptyList(), 0, false, 0, 0, 0, emptyList(), false,
            0.0, GalaxyUiFpsState.STATIC)
        assertEquals(app.getString(R.string.performance_no_stream), GalaxyPerformanceFpsLabels.carPlay(app, sample))
        assertEquals(app.getString(R.string.performance_ui_idle), GalaxyPerformanceFpsLabels.ui(app, sample))
        val panel = GalaxyPerformanceOverlay.panelText(sample, app)
        assertTrue(panel.contains("UFPS")); assertTrue(panel.contains("CFPS"))
        assertTrue(panel.contains(app.getString(R.string.performance_no_stream_short)))
        val waiting = sample.copy(input = input.copy(carPlayConnected = true))
        assertEquals(app.getString(R.string.performance_video_wait), GalaxyPerformanceFpsLabels.carPlay(app, waiting))
        assertEquals(2, panel.lines().size)
    }
}
