package com.shilapi.xcertplay

import android.app.Activity
import android.graphics.Rect
import android.view.View
import android.widget.FrameLayout
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30])
class GalaxyProjectionWindowTest {
    @Test fun fullPanelAndAlreadyReducedCarWindowHaveTheSameAvailablePixels() {
        for (top in listOf(0, 48)) for (bottom in listOf(0, 176)) {
            val bars = Insets.of(0, top, 0, bottom)
            val full = GalaxyProjectionWindow.overlap(Rect(0, 0, 1440, 1920), 1440, 1920, bars)
            val reduced = GalaxyProjectionWindow.overlap(Rect(0, top, 1440, 1920 - bottom), 1440, 1920, bars)
            assertEquals(bars, full)
            assertEquals(Insets.NONE, reduced)
            assertEquals(1920 - top - bottom, 1920 - full.top - full.bottom)
        }
    }

    @Test fun partialOverlapAndLandscapeSideNavigationReserveOnlyCoveredPixels() {
        assertEquals(Insets.of(0, 28, 0, 76), GalaxyProjectionWindow.overlap(
            Rect(0, 20, 1440, 1820), 1440, 1920, Insets.of(0, 48, 0, 176)))
        assertEquals(Insets.of(0, 0, 120, 0), GalaxyProjectionWindow.overlap(
            Rect(0, 0, 1920, 1440), 1920, 1440, Insets.of(0, 0, 120, 0)))
        assertEquals(Insets.NONE, GalaxyProjectionWindow.overlap(
            Rect(0, 0, 700, 1440), 1920, 1440, Insets.of(0, 0, 120, 0)))
    }

    @Test fun videoAndGestureLayersShareTheSettledRegionAndTransientBarsDoNotResizeIt() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        // 固定完整面板原点，避免 Robolectric 初始 Decor 的旧偏移混入本布局夹具。
        val root = object : FrameLayout(activity) {
            override fun getLocationOnScreen(location: IntArray) { location.fill(0) }
        }
        val video = View(activity)
        val gesture = View(activity)
        root.addView(video, FrameLayout.LayoutParams(-1, -1))
        root.addView(gesture, FrameLayout.LayoutParams(-1, -1))
        var callbacks = 0
        val geometry = mutableListOf<String>()
        val window = GalaxyProjectionWindow(root, report = geometry::add) { callbacks++ }
        activity.setContentView(root)
        shadowOf(root.display).setRealWidth(1440)
        shadowOf(root.display).setRealHeight(1920)
        fun layout() {
            root.measure(View.MeasureSpec.makeMeasureSpec(1440, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1440, 1920)
            root.viewTreeObserver.dispatchOnGlobalLayout()
        }
        val insets = WindowInsetsCompat.Builder().setSystemWindowInsets(Insets.of(0, 48, 0, 176))
            .setStableInsets(Insets.of(0, 48, 0, 176))
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 48, 0, 0))
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 176)).build()
        assertEquals(48, insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars()).top)
        assertEquals(176, insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars()).bottom)
        for (hideTop in listOf(false, true)) for (hideBottom in listOf(false, true)) {
            window.configure(hideTop, hideBottom)
            ViewCompat.dispatchApplyWindowInsets(root, insets)
            layout(); layout()
            assertTrue(window.ready)
            assertEquals("$geometry padding=${root.paddingTop} child=${video.top}", if (hideTop) 0 else 48, video.top)
            assertEquals(1920 - (if (hideTop) 0 else 48) - (if (hideBottom) 0 else 176), video.height)
            assertEquals(video.width, gesture.width)
            assertEquals(video.height, gesture.height)
            assertEquals(video.top, gesture.top)
        }
        assertEquals(1920, video.height)
        assertTrue(callbacks > 0)
        activity.finish()
    }
}
