package com.shilapi.xcertplay

import android.content.Intent
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30])
class GalaxyPerformanceServiceTest {
    @After fun reset() {
        GalaxyUiFrameMonitor.stop()
        GalaxyPerformanceState.running = false; GalaxyPerformanceState.overlayVisible = false
        GalaxyPerformanceState.overlayWanted = true; GalaxyPerformanceState.displayFailed = false
        GalaxyPerformanceState.overlayPermission = false
        GalaxyPerformanceState.latest = null; GalaxyPerformanceState.history = emptyList()
        L7DebugLog.performanceMonitored = false
        com.shilapi.xcertplay.media.GalaxyPerformanceObservation.active = false
    }
    @Test fun nullRestartIntentCannotStartSamplingOrResumePreviousMonitoring() {
        val controller = Robolectric.buildService(GalaxyPerformanceService::class.java).create()
        try {
            controller.get().onStartCommand(null, 0, 1)
            assertNull(ReflectionHelpers.getField<Any?>(controller.get(), "monitor"))
            assertFalse(GalaxyPerformanceState.running)
        } finally { controller.destroy() }
    }
    @Test fun explicitStartWithoutOverlayPermissionSamplesInAppAndActuallyStopsWorker() {
        val app = RuntimeEnvironment.getApplication()
        L7Agreement.accept(app); ShadowSettings.setCanDrawOverlays(false)
        val controller = Robolectric.buildService(GalaxyPerformanceService::class.java).create()
        var monitor: GalaxyPerformanceMonitor? = null
        try {
            val intent = Intent(app, GalaxyPerformanceService::class.java).setAction("com.ecarx.carplay.PERFORMANCE_START")
            controller.get().onStartCommand(intent, 0, 1)
            monitor = ReflectionHelpers.getField(controller.get(), "monitor")
            assertNotNull(monitor); assertTrue(GalaxyPerformanceState.running)
            controller.get().onStartCommand(intent, 0, 2)
            assertSame(monitor, ReflectionHelpers.getField<Any>(controller.get(), "monitor"))
            assertNull(ReflectionHelpers.getField<Any?>(controller.get(), "overlay"))
            assertFalse(GalaxyPerformanceState.overlayVisible)
        } finally {
            controller.destroy(); assertTrue(monitor?.awaitClosed(2000) ?: true)
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertFalse(GalaxyPerformanceState.running)
        assertFalse(L7DebugLog.performanceMonitored)
        assertFalse(com.shilapi.xcertplay.media.GalaxyPerformanceObservation.active)
    }
    @Test fun overlayParametersAlwaysPassTouchesAndCannotTakeFocus() {
        val overlay = GalaxyPerformanceOverlay(RuntimeEnvironment.getApplication()) { }
        val layout = overlay.layout
        assertTrue(layout.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        assertTrue(layout.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        assertEquals(Gravity.TOP or Gravity.LEFT, layout.gravity)
        assertTrue(layout.alpha < 0.8f)
        assertEquals(WindowManager.LayoutParams.WRAP_CONTENT, layout.width)
        assertEquals(WindowManager.LayoutParams.WRAP_CONTENT, layout.height)
        overlay.close()
    }
    @Test @Config(sdk = [29])
    @Suppress("DEPRECATION")
    fun legacyWindowRelativeInsetsCannotMoveTheOverlayBackAndForth() {
        val overlay = GalaxyPerformanceOverlay(RuntimeEnvironment.getApplication()) { }
        fun apply(top: Int) = ReflectionHelpers.callInstanceMethod<Unit>(overlay, "position",
            ReflectionHelpers.ClassParameter.from(android.view.WindowInsets::class.java,
                android.view.WindowInsets.Builder().setSystemWindowInsets(android.graphics.Insets.of(0, top, 0, 0)).build()))
        apply(38)
        val safeTop = overlay.layout.y
        repeat(3) { apply(0); assertEquals(safeTop, overlay.layout.y); apply(38) }
        assertTrue(safeTop >= 38)
        overlay.close()
    }
    @Test fun unknownPresentationAndSeparateHeapsAreVisibleWithoutFabricatedFpsOrMemoryTotal() {
        val input = GalaxyPerformanceInput(0, 0, 10 * 1048576, 3 * 1048576, null, null, null, null, null, null, emptyList(), emptyList())
        val sample = GalaxyPerformanceSample(input, 150.0, emptyList(), 0, false, 0, 0, 0, emptyList(), false)
        val text = GalaxyPerformanceOverlay.panelText(sample, RuntimeEnvironment.getApplication())
        assertTrue(text.contains("UFPS")); assertTrue(text.contains("CFPS"));
        assertTrue(text.contains("No CP") || text.contains("未投屏")); assertTrue(text.contains("150%")); assertTrue(text.contains("J   10M"))
        assertTrue(text.contains("N    3M")); assertFalse(text.contains("13M"))
        assertEquals(2, text.lines().size)
    }
}
