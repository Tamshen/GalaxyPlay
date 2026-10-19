package com.shilapi.xcertplay

import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import androidx.activity.ComponentActivity
import com.shilapi.xcertplay.host.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class GalaxyMediaReportingPreferencesTest {
    private val key = "galaxy_media_reporting_enabled"
    private val obsolete = "galaxy_media_reporting_channel"
    private fun activity() = Robolectric.buildActivity(ComponentActivity::class.java).setup().get().apply {
        setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        java.io.File(filesDir, "configurations").deleteRecursively()
        getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    @Test fun reportingPageHasOneMediaSwitchEnabledByDefault() {
        val context = activity()
        val page = GalaxyConfigurationPage(context) {}
        page.view.tabs.buttons.first { it.text == context.getString(R.string.config_page_reporting) }.performClick()
        val rows = descendants(page.view.fields).filterIsInstance<L7SettingRow>()
        assertEquals(listOf(context.getString(R.string.config_media_reporting),
            context.getString(R.string.config_navigation_reporting), context.getString(R.string.report_location_to_iphone)),
            rows.map { it.titleView.text.toString() })
        val row = rows.first()
        assertTrue(descendants(row).filterIsInstance<Switch>().single().isChecked)
        row.performClick()
        assertTrue(page.dirty)
        assertTrue(GalaxyVehiclePreferences.mediaReporting(context))
        page.close()
        assertTrue(GalaxyVehiclePreferences.mediaReporting(context))
    }

    @Test fun savedSwitchFreezesExistingSessionAndResetRestoresRegistration() {
        val context = activity()
        val repository = GalaxyProfiles(context)
        val original = repository.applyTemplate("l6")
        val session = GalaxyConfigurationContext(context, original, runtimeOnly = true)
        val draft = GalaxyConfigurationContext(context, original, editable = true)
        draft.getSharedPreferences("xcertplay_airplay", 0).edit().putBoolean(key, false).commit()
        assertTrue(GalaxyVehiclePreferences.mediaReporting(context))
        val saved = repository.save(original.copy(configuration = draft.configuration()))
        assertTrue(GalaxyVehiclePreferences.mediaReporting(session))
        assertFalse(GalaxyVehiclePreferences.mediaReporting(context))
        assertFalse(GalaxyVehiclePreferences.mediaReporting(GalaxyConfigurationContext(context, saved, runtimeOnly = true)))
        repository.resetCurrent(saved)
        assertTrue(GalaxyVehiclePreferences.mediaReporting(context))
    }

    @Test fun oldChannelsAreDiscardedWithoutChangingExplicitRegistrationSwitchOrOtherSettings() {
        val context = activity()
        for (legacy in listOf("CURRENT", "NETEASE_COMPAT", "QQ_COMPAT", "LOCAL", "unrecognized")) {
            for (enabled in listOf(false, true)) {
                val original = GalaxyConfigurationFields.factory(context, "l7")
                val main = original.preferences.getValue("xcertplay_airplay") + mapOf(
                    obsolete to legacy, key to enabled, "display_fps" to 30)
                val profile = GalaxyProfile("legacy_media", "Legacy", 1, 1,
                    original.copy(preferences = original.preferences + ("xcertplay_airplay" to main)))
                val loaded = GalaxyProfile.parse(profile.json().toString())
                val scope = GalaxyConfigurationContext(context, loaded, runtimeOnly = true)
                assertEquals(enabled, GalaxyVehiclePreferences.mediaReporting(scope))
                assertEquals(30, scope.getSharedPreferences("xcertplay_airplay", 0).getInt("display_fps", 0))
                assertFalse(scope.configuration().preferences.getValue("xcertplay_airplay").containsKey(obsolete))
            }
        }
        for (template in listOf("l7", "l6", "custom")) {
            val factory = GalaxyProfile("factory_media", "Factory", 1, 1, GalaxyConfigurationFields.factory(context, template))
            assertTrue(GalaxyVehiclePreferences.mediaReporting(GalaxyConfigurationContext(context, factory, runtimeOnly = true)))
        }
    }
}
