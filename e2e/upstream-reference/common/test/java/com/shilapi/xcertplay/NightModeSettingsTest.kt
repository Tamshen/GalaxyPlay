package com.shilapi.xcertplay

import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], qualifiers = "en", manifest = Config.NONE)
class NightModeSettingsTest {
    private lateinit var controller: ActivityController<DiPlayActivity>
    private lateinit var activity: DiPlayActivity
    private lateinit var page: LinearLayout

    @Before fun setUp() {
        controller = Robolectric.buildActivity(DiPlayActivity::class.java)
        activity = controller.get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        activity.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        CarPlayBackgroundSession.clear()
        controller.setup().visible()
    }

    @After fun tearDown() {
        controller.pause().stop().destroy()
        CarPlayBackgroundSession.clear()
    }

    @Test fun savedModesShowAmbientSettingsOnlyForAmbientLight() {
        for (mode in CarPlayNightMode.entries) {
            AirPlayPersistence.saveCarPlayNightMode(activity, mode)
            renderSettings()
            assertAmbientVisible(mode == CarPlayNightMode.AMBIENT)
        }
    }

    @Test fun savingModeUpdatesVisibilityInPlaceAndKeepsEditedValues() {
        AirPlayPersistence.saveCarPlayNightMode(activity, CarPlayNightMode.AMBIENT)
        renderSettings()
        editNumber(R.string.ambient_light_threshold_title, 75)
        editNumber(R.string.ambient_delay_title, 7)
        val threshold = button(R.string.ambient_light_threshold_title)
        val delay = button(R.string.ambient_delay_title)

        for (mode in listOf(CarPlayNightMode.SYSTEM, CarPlayNightMode.DAY,
            CarPlayNightMode.NIGHT, CarPlayNightMode.AMBIENT)) {
            selectMode(mode).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(mode, AirPlayPersistence.loadCarPlayNightMode(activity))
            assertAmbientVisible(mode == CarPlayNightMode.AMBIENT)
            assertSame(threshold, button(R.string.ambient_light_threshold_title))
            assertSame(delay, button(R.string.ambient_delay_title))
            assertEquals(75, AirPlayPersistence.loadAmbientLightThreshold(activity).lux)
            assertEquals(7, AirPlayPersistence.loadAmbientDelaySeconds(activity))
        }
        renderSettings()
        assertAmbientVisible(true)
        assertTrue(button(R.string.ambient_light_threshold_title).text.toString().endsWith("75 lux"))
        assertTrue(button(R.string.ambient_delay_title).text.toString().endsWith("7 s"))
    }

    @Test fun cancellingModeChoiceKeepsSavedModeAndVisibility() {
        for (mode in listOf(CarPlayNightMode.SYSTEM, CarPlayNightMode.AMBIENT)) {
            AirPlayPersistence.saveCarPlayNightMode(activity, mode)
            renderSettings()
            val target = if (mode == CarPlayNightMode.SYSTEM) CarPlayNightMode.AMBIENT else CarPlayNightMode.SYSTEM
            val dialog = selectMode(target)
            assertAmbientVisible(mode == CarPlayNightMode.AMBIENT)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(mode, AirPlayPersistence.loadCarPlayNightMode(activity))
            assertAmbientVisible(mode == CarPlayNightMode.AMBIENT)
        }
    }

    private fun renderSettings() {
        page = LinearLayout(activity)
        DiPlayActivity::class.java.getDeclaredMethod("settings", LinearLayout::class.java)
            .apply { isAccessible = true }.invoke(activity, page)
        activity.setContentView(page)
    }

    private fun assertAmbientVisible(expected: Boolean) {
        for (id in listOf(R.string.ambient_light_threshold_title, R.string.ambient_delay_title)) {
            assertEquals(activity.getString(id), expected, button(id).isShown)
        }
        assertEquals(expected, label(R.string.carplay_night_ambient_hint).isShown)
        for (id in listOf(R.string.carplay_night_hint, R.string.carplay_night_time_note, R.string.picture_adjustments)) {
            assertTrue(activity.getString(id), label(id).isShown)
        }
    }

    private fun selectMode(mode: CarPlayNightMode): AlertDialog {
        button(R.string.carplay_night_mode).performClick()
        return ShadowAlertDialog.getLatestAlertDialog().also {
            it.listView.performItemClick(null, CarPlayNightMode.entries.indexOf(mode), 0)
        }
    }

    private fun editNumber(title: Int, value: Int) {
        button(title).performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        shadowOf(Looper.getMainLooper()).idle()
        views(dialog.window!!.decorView).filterIsInstance<EditText>().single().setText(value.toString())
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun button(title: Int) = views(page).filterIsInstance<Button>().single {
        it.text.startsWith(activity.getString(title) + " · ")
    }

    private fun label(text: Int) = views(page).filterIsInstance<TextView>().single {
        it.text == activity.getString(text)
    }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }
}
