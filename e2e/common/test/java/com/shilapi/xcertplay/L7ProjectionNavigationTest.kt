package com.shilapi.xcertplay

import android.app.Activity
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "zh-rCN")
class L7ProjectionNavigationTest {
    private fun activity() = Robolectric.buildActivity(Activity::class.java).setup().get().apply {
        setTheme(android.R.style.Theme_Material_Light_NoActionBar)
    }
    private fun layout(view: View, width: Int = 1000, height: Int = 1400) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }
    private fun touch(view: View, action: Int, x: Float, y: Float) {
        MotionEvent.obtain(0, 100, action, x, y, 0).let {
            view.dispatchTouchEvent(it); it.recycle()
        }
    }

    private fun actions(view: View): List<View> =
        (if (view.isClickable && !view.contentDescription.isNullOrEmpty()) listOf(view) else emptyList()) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { actions(view.getChildAt(it)) } else emptyList()

    @Test fun compactMenuHasFourActionsAndPreservesTheirNavigation() {
        val context = activity()
        val destinations = mutableListOf<String>()
        val overlay = L7ProjectionNavigation(context, {}) { destinations += it }
        layout(overlay)
        val panel = overlay.getChildAt(1)
        val buttons = actions(panel)
        assertEquals(listOf("画面", "设置", "车机", "退出"), buttons.map { it.contentDescription.toString() })
        buttons[0].performClick()
        assertFalse(overlay.expanded)
        overlay.expand()
        buttons[1].performClick()
        assertFalse(overlay.expanded)
        overlay.expand()
        buttons[2].performClick()
        assertFalse(overlay.expanded)
        overlay.expand()
        buttons[3].performClick()
        assertTrue(overlay.expanded)
        assertEquals(listOf("home", "settings", "car-home", "exit"), destinations)
    }

    @Test fun connectedCollapseAndManualExpansionKeepFullVideoBounds() {
        val context = activity()
        val root = FrameLayout(context)
        val video = View(context)
        val overlay = L7ProjectionNavigation(context, {}) {}
        root.addView(video, FrameLayout.LayoutParams(-1, -1))
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        layout(root)
        val panel = overlay.getChildAt(1)
        assertTrue(panel.left > 0 && panel.top > 0)
        assertTrue(panel.bottom < overlay.height)
        assertTrue(panel.clipToOutline)
        overlay.setConnected(true)
        assertFalse(overlay.expanded)
        assertEquals(View.VISIBLE, overlay.handle.visibility)
        overlay.expand()
        overlay.setConnected(true)
        assertTrue(overlay.expanded)
        layout(root)
        assertEquals(1000, video.width)
        assertEquals(1400, video.height)
        overlay.collapse()
        overlay.setConnected(false)
        assertTrue(overlay.expanded)
    }

    @Test fun collapsedOverlayPassesTouchesButExpandedPanelConsumesOutsideTap() {
        val context = activity()
        var videoTouches = 0
        val root = FrameLayout(context)
        root.addView(View(context).apply { setOnTouchListener { _, _ -> videoTouches++; true } },
            FrameLayout.LayoutParams(-1, -1))
        val overlay = L7ProjectionNavigation(context, {}) {}
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        context.setContentView(root)
        layout(root)
        overlay.collapse()
        touch(root, MotionEvent.ACTION_DOWN, 900f, 1200f)
        touch(root, MotionEvent.ACTION_UP, 900f, 1200f)
        assertEquals(2, videoTouches)
        overlay.expand()
        touch(root, MotionEvent.ACTION_DOWN, 900f, 1200f)
        touch(root, MotionEvent.ACTION_UP, 900f, 1200f)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, videoTouches)
        assertFalse(overlay.expanded)
    }

    @Test fun settingsDockFullHeightAndPreserveButtonsWithoutBlockingRightColumn() {
        val context = activity()
        var contentTouches = 0
        val root = FrameLayout(context)
        root.addView(View(context).apply { setOnTouchListener { _, _ -> contentTouches++; true } },
            FrameLayout.LayoutParams(-1, -1))
        val overlay = L7ProjectionNavigation(context, {}) {}
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        context.setContentView(root)
        layout(root)
        val panel = overlay.getChildAt(1)
        val buttons = actions(panel)
        val bounds = listOf(panel.left, panel.top, panel.width, panel.height)
        fun buttonBounds() = buttons.map { button ->
            val position = IntArray(2).also(button::getLocationInWindow)
            listOf(position[0], position[1], button.width, button.height)
        }
        val originalButtons = buttonBounds()
        listOf("settings", "settings-display", "settings-diagnostics").forEach { page ->
            overlay.showPage(page)
            layout(root)
            assertTrue(overlay.expanded)
            assertEquals(listOf(0, 0, L7ProjectionNavigation.settingsColumnWidth(context), root.height),
                listOf(panel.left, panel.top, panel.width, panel.height))
            assertEquals(originalButtons, buttonBounds())
            assertEquals(0f, panel.elevation, .001f)
            buttons.zip(actions(panel)).forEach { (before, after) -> assertSame(before, after) }
            assertEquals(listOf("设置"), buttons.filter { it.isSelected }.map { it.contentDescription.toString() })
        }
        touch(root, MotionEvent.ACTION_DOWN, 900f, 1200f)
        touch(root, MotionEvent.ACTION_UP, 900f, 1200f)
        assertEquals(2, contentTouches)
        assertTrue(overlay.expanded)
        // 固定左栏的空白区域也不透传到正文，右栏仍可滚动。
        touch(root, MotionEvent.ACTION_DOWN, 20f, 1300f)
        touch(root, MotionEvent.ACTION_UP, 20f, 1300f)
        assertEquals(2, contentTouches)
        overlay.showPage("home")
        assertFalse(overlay.expanded)
        overlay.expand()
        layout(root)
        assertEquals(bounds, listOf(panel.left, panel.top, panel.width, panel.height))
        assertTrue(buttons[0].isSelected)
        assertFalse(buttons[1].isSelected)
    }

    @Test fun dragClampsPersistsAndKeepsMenuFixedOnLeft() {
        val context = activity()
        val overlay = L7ProjectionNavigation(context, {}) {}
        layout(overlay)
        val initialPanel = overlay.getChildAt(1)
        val originalBounds = listOf(initialPanel.x, initialPanel.y, initialPanel.width.toFloat(), initialPanel.height.toFloat())
        overlay.collapse()
        touch(overlay.handle, MotionEvent.ACTION_DOWN, 10f, 10f)
        touch(overlay.handle, MotionEvent.ACTION_MOVE, 3000f, -3000f)
        touch(overlay.handle, MotionEvent.ACTION_UP, 3000f, -3000f)
        assertFalse(overlay.expanded)
        assertEquals(1f to 0f, L7FloatingNavigationPreferences.position(context))
        overlay.handle.performClick()
        layout(overlay)
        assertTrue(overlay.expanded)
        assertEquals(originalBounds, listOf(initialPanel.x, initialPanel.y, initialPanel.width.toFloat(), initialPanel.height.toFloat()))
        val restored = L7ProjectionNavigation(context, {}) {}
        layout(restored, 600, 800)
        restored.collapse()
        layout(restored, 600, 800)
        assertTrue(restored.handle.x >= 0f)
        assertTrue(restored.handle.x + restored.handle.width <= 600f)
        assertTrue(restored.handle.y >= 0f)
        restored.handle.performClick()
        layout(restored, 600, 800)
        assertTrue(restored.expanded)
        val panel = restored.getChildAt(1)
        assertEquals(initialPanel.x, panel.x, .001f)
        assertTrue(panel.x + panel.width < restored.width / 2f)
        assertEquals(initialPanel.y, panel.y, .001f)
        assertEquals(panel.x, panel.y, .001f)
        assertTrue(panel.x >= 0f && panel.x + panel.width <= restored.width)
        assertTrue(panel.y >= 0f && panel.y + panel.height <= restored.height)
    }

    @Test fun transparencyRefreshDoesNotResetPanelStateOrPosition() {
        val context = activity()
        val overlay = L7ProjectionNavigation(context, {}) {}
        layout(overlay)
        overlay.collapse()
        val x = overlay.handle.x
        val y = overlay.handle.y
        L7FloatingNavigationPreferences.saveTransparency(context, 75)
        overlay.refreshAppearance()
        assertEquals(.25f, overlay.handle.alpha, .001f)
        assertFalse(overlay.expanded)
        assertEquals(x, overlay.handle.x, .001f)
        assertEquals(y, overlay.handle.y, .001f)
    }
}
