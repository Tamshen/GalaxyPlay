package com.shilapi.xcertplay

import android.app.Activity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30], qualifiers = "zh-rCN", shadows = [HostConnectionSettingsRefreshTest.Bootstrap::class])
class L7FloatingNavigationModeTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @Before fun reset() {
        GalaxyConfigurationFields.names.forEach { app.getSharedPreferences(it, 0).edit().clear().commit() }
        java.io.File(app.filesDir, "configurations").deleteRecursively()
    }
    private fun activity() = Robolectric.buildActivity(Activity::class.java).setup().get().apply {
        setTheme(android.R.style.Theme_Material_Light_NoActionBar)
    }
    private fun layout(view: View) {
        view.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1400, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 1000, 1400)
    }
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
        else emptyList()
    private fun touch(view: View, action: Int, x: Float, y: Float) {
        MotionEvent.obtain(0, 100, action, x, y, 0).let { view.dispatchTouchEvent(it); it.recycle() }
    }
    @Test fun oldDesktopPreferenceAndNewInAppPreferenceAreIndependentAndSurviveTemplates() {
        assertTrue(L7FloatingNavigationPreferences.inAppEnabled(app))
        assertTrue(L7DesktopNavigation.enabled(app))
        app.getSharedPreferences("l7_floating_navigation", 0).edit().putBoolean("desktop", false).commit()
        assertTrue(L7FloatingNavigationPreferences.inAppEnabled(app))
        L7FloatingNavigationPreferences.setInAppEnabled(app, false)
        assertFalse(L7DesktopNavigation.enabled(app))
        app.getSharedPreferences("l7_floating_navigation", 0).edit().putBoolean("desktop", true).commit()
        assertFalse(L7FloatingNavigationPreferences.inAppEnabled(app))
        val repository = GalaxyProfiles(app)
        listOf("l7", "l6", "custom").forEach { model ->
            val profile = repository.applyTemplate(model)
            repository.resetCurrent(profile)
            repository.save(repository.active())
            assertFalse(L7FloatingNavigationPreferences.inAppEnabled(app))
            assertTrue(L7DesktopNavigation.enabled(app))
        }
        val evidence = GalaxyConfigurationEvidence.capture(app)
        val prefs = JSONObject(evidence.text).getJSONObject("application_preferences").getJSONObject("l7_floating_navigation")
        assertFalse(prefs.getJSONObject("in_app").getBoolean("value"))
        assertTrue(prefs.getJSONObject("desktop").getBoolean("value"))
        assertEquals(evidence, GalaxyConfigurationEvidence.read(evidence.text))
        assertFalse(repository.active().configuration.preferences.getValue("l7_floating_navigation").containsKey("in_app"))
    }
    @Test fun applicationPageExposesTwoIndependentSwitchesWithoutInAppOverlayPermission() {
        val context = activity()
        val parent = android.widget.LinearLayout(context)
        L7DisplaySettings.addApplication(context, parent)
        val controls = descendants(parent).filterIsInstance<android.widget.Switch>()
        val inApp = controls.first { it.contentDescription == context.getString(com.shilapi.xcertplay.host.R.string.l7_in_app_title) }
        val desktop = controls.first { it.contentDescription == context.getString(com.shilapi.xcertplay.host.R.string.l7_desktop_title) }
        inApp.performClick()
        assertFalse(L7FloatingNavigationPreferences.inAppEnabled(context))
        assertTrue(desktop.isChecked)
        assertTrue(L7DesktopNavigation.enabled(context))
        assertNull(shadowOf(context).nextStartedActivity)
        desktop.performClick()
        assertFalse(L7DesktopNavigation.enabled(context))
        inApp.performClick()
        assertTrue(L7FloatingNavigationPreferences.inAppEnabled(context))
        assertFalse(desktop.isChecked)
        assertNull(shadowOf(context).nextStartedActivity)
    }
    @Test fun disabledProjectionPassesAllTouchesAndBackReturnsHomeWithoutChangingVideoBounds() {
        val context = activity()
        L7FloatingNavigationPreferences.setInAppEnabled(context, false)
        var homeRequests = 0
        var touches = 0
        val video = View(context).apply { setOnTouchListener { _, _ -> touches++; true } }
        val overlay = L7ProjectionNavigation(context, {}, projection = true, returnHome = { homeRequests++ }) {}
        val root = FrameLayout(context).apply {
            addView(video, FrameLayout.LayoutParams(-1, -1))
            addView(overlay, FrameLayout.LayoutParams(-1, -1))
        }
        context.setContentView(root)
        layout(root)
        listOf(false, true, false).forEach { connected ->
            overlay.setConnected(connected)
            assertFalse(overlay.expanded)
            (0 until overlay.childCount).forEach { assertEquals(View.GONE, overlay.getChildAt(it).visibility) }
        }
        touch(root, MotionEvent.ACTION_DOWN, 20f, 20f)
        touch(root, MotionEvent.ACTION_UP, 20f, 20f)
        touch(root, MotionEvent.ACTION_DOWN, 900f, 1200f)
        touch(root, MotionEvent.ACTION_UP, 900f, 1200f)
        assertEquals(4, touches)
        overlay.expand()
        assertEquals(1, homeRequests)
        assertFalse(overlay.expanded)
        assertEquals(1000, video.width)
        assertEquals(1400, video.height)
    }
    @Test fun disabledHomeKeepsMenuAccessibleAndSettingsKeepFixedRail() {
        val context = activity()
        L7FloatingNavigationPreferences.setInAppEnabled(context, false)
        val destinations = mutableListOf<String>()
        val overlay = L7ProjectionNavigation(context, {}) { destinations += it }
        overlay.showPage("home")
        overlay.setConnected(true)
        overlay.collapse()
        assertTrue(overlay.expanded)
        assertEquals(View.VISIBLE, overlay.getChildAt(1).visibility)
        assertEquals(View.GONE, overlay.getChildAt(0).visibility)
        assertEquals(View.GONE, overlay.handle.visibility)
        descendants(overlay.getChildAt(1)).first { it.isClickable && it.contentDescription == "设置" }.performClick()
        assertEquals(listOf("settings"), destinations)
        overlay.showPage("settings-general")
        layout(overlay)
        assertTrue(overlay.expanded)
        assertEquals(1400, overlay.getChildAt(1).height)
        assertEquals(View.GONE, overlay.getChildAt(0).visibility)
    }
    @Test fun returningToProjectionRefreshesToggleWithoutRecreatingOverlay() {
        val context = activity()
        var homes = 0
        val overlay = L7ProjectionNavigation(context, {}, projection = true, returnHome = { homes++ }) {}
        overlay.setConnected(true)
        val panel = overlay.getChildAt(1)
        assertEquals(View.VISIBLE, overlay.handle.visibility)
        L7FloatingNavigationPreferences.setInAppEnabled(context, false)
        overlay.refreshAppearance()
        assertEquals(View.GONE, overlay.handle.visibility)
        overlay.expand()
        assertEquals(1, homes)
        L7FloatingNavigationPreferences.setInAppEnabled(context, true)
        overlay.refreshAppearance()
        assertSame(panel, overlay.getChildAt(1))
        assertFalse(overlay.expanded)
        assertEquals(View.VISIBLE, overlay.handle.visibility)
        overlay.expand()
        assertTrue(overlay.expanded)
        assertEquals(1, homes)
    }
    @Test @Config(qualifiers = "w720dp-h960dp-xhdpi")
    fun disabledHomePanelFitsBesideFixedMenuAtLargeUiSize() {
        L7Agreement.accept(app)
        L7UiDensity.save(app, 320)
        L7FloatingNavigationPreferences.setInAppEnabled(app, false)
        val home = Robolectric.buildActivity(GalaxySettingsActivity::class.java).setup().get()
        val root = home.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(1440, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1440, 1920)
        val panel = GalaxySettingsActivity::class.java.getDeclaredField("homePanel")
            .apply { isAccessible = true }.get(home) as View
        val parent = panel.parent as View
        assertTrue(panel.left >= parent.paddingLeft)
        assertTrue(panel.right <= parent.width - parent.paddingRight)
        val location = IntArray(2).also(panel::getLocationInWindow)
        assertTrue(location[0] >= L7ProjectionNavigation.settingsColumnWidth(home))
    }
    @Test fun actualHostBackRoutesToHomeWithoutStoppingHostOrChangingController() {
        L7Agreement.accept(app)
        L7FloatingNavigationPreferences.setInAppEnabled(app, false)
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).setup().get()
        fun field(name: String) = CarPlayHostActivity::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(host)
        val controller = field("controller")
        val generation = field("restartGeneration")
        host.onBackPressedDispatcher.onBackPressed()
        val intent = shadowOf(host).nextStartedActivity
        assertEquals(GalaxySettingsActivity::class.java.name, intent.component!!.className)
        assertEquals("home", intent.getStringExtra("page"))
        assertFalse(host.isFinishing)
        assertSame(controller, field("controller"))
        assertEquals(generation, field("restartGeneration"))
    }
}
