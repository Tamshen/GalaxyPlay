package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.MediaPerformanceOptions
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30])
class GalaxyMediaPerformanceSettingsTest {
    private fun activity() = Robolectric.buildActivity(Activity::class.java).setup().get().apply {
        setTheme(android.R.style.Theme_Material_Light_NoActionBar)
    }

    @Test fun allDefaultAndIndependentChangesOnlyApplyToNewSinkAndSurviveTemplates() {
        val activity = activity()
        val items = GalaxyMediaPerformancePreferences.Item.entries
        assertEquals(MediaPerformanceOptions(), GalaxyMediaPerformancePreferences.snapshot(activity))
        val sink = AndroidMediaSink(performanceOptions = GalaxyMediaPerformancePreferences.snapshot(activity))
        try {
            GalaxyMediaPerformancePreferences.save(activity, items[0], true)
            val selected = GalaxyMediaPerformancePreferences.snapshot(activity)
            assertEquals(MediaPerformanceOptions(separateMusicWriter = true), selected)
            assertEquals(MediaPerformanceOptions(), ReflectionHelpers.getField<MediaPerformanceOptions>(sink, "performanceOptions"))
            val profiles = GalaxyProfiles(activity)
            profiles.applyTemplate("l7")
            profiles.applyTemplate("l6")
            assertEquals(selected, GalaxyMediaPerformancePreferences.snapshot(activity))
            items.forEach { GalaxyMediaPerformancePreferences.save(activity, it, false) }
            val reconnected = AndroidMediaSink(performanceOptions = GalaxyMediaPerformancePreferences.snapshot(activity))
            try {
                assertEquals(MediaPerformanceOptions(), ReflectionHelpers.getField<MediaPerformanceOptions>(reconnected, "performanceOptions"))
                assertEquals(MediaPerformanceOptions(separateMusicWriter = true), selected)
            } finally { reconnected.close() }
        } finally { sink.close() }
    }

    @Test fun cancelDoesNotSaveAndConfirmationInProfilePageSavesIndependentPreference() {
        val activity = activity()
        val profile = GalaxyProfiles(activity).applyTemplate("l7")
        val draft = GalaxyConfigurationContext(activity, profile, editable = true)
        val parent = LinearLayout(draft)
        GalaxyMediaPerformanceSettings.add(draft, parent)
        val rows = rows(parent)
        assertEquals(3, rows.size)
        fun choose(confirm: Boolean) {
            rows[1].performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertEquals(activity.getString(R.string.l7_save_next_connection),
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
            dialog.listView.performItemClick(dialog.listView.adapter.getView(1, null, dialog.listView), 1, 1)
            dialog.getButton(if (confirm) AlertDialog.BUTTON_POSITIVE else AlertDialog.BUTTON_NEGATIVE).performClick()
        }
        choose(false)
        assertEquals(MediaPerformanceOptions(), GalaxyMediaPerformancePreferences.snapshot(activity))
        choose(true)
        assertEquals(MediaPerformanceOptions(reuseVideoBuffer = true), GalaxyMediaPerformancePreferences.snapshot(activity))
        assertEquals(profile.configuration, draft.configuration())
        activity.getSharedPreferences("galaxy_media_performance", 0).edit()
            .putString(GalaxyMediaPerformancePreferences.Item.LOW_LATENCY.key, "unknown").commit()
        assertFalse(GalaxyMediaPerformancePreferences.snapshot(activity).qtiLowLatency)
    }

    private fun rows(view: View): List<L7SettingRow> = when (view) {
        is L7SettingRow -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { rows(view.getChildAt(it)) }
        else -> emptyList()
    }
}
