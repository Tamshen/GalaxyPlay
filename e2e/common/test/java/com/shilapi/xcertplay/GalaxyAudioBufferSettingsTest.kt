package com.shilapi.xcertplay

import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import com.shilapi.xcertplay.host.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class GalaxyAudioBufferSettingsTest {
    private fun rows(view: View): List<L7SettingRow> = if (view is L7SettingRow) listOf(view)
        else if (view is ViewGroup) (0 until view.childCount).flatMap { rows(view.getChildAt(it)) } else emptyList()
    private fun dialog(): AlertDialog {
        shadowOf(Looper.getMainLooper()).idle()
        return ShadowAlertDialog.getLatestAlertDialog()
    }
    private fun choose(row: L7SettingRow, index: Int, confirm: Boolean) {
        row.performClick()
        val picker = dialog()
        picker.listView.performItemClick(picker.listView.adapter.getView(index, null, picker.listView), index, index.toLong())
        picker.getButton(if (confirm) AlertDialog.BUTTON_POSITIVE else AlertDialog.BUTTON_NEGATIVE).performClick()
    }

    @Test fun purposeChoicesCancelOrUpdateDraftAndOnlySaveAffectsFutureConnections() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get().apply {
            setTheme(android.R.style.Theme_Material_Light_NoActionBar)
            java.io.File(filesDir, "configurations").deleteRecursively()
        }
        val repository = GalaxyProfiles(activity)
        val original = repository.active()
        val connected = GalaxyConfigurationContext(activity, original)
        val page = GalaxyConfigurationPage(activity) {}
        try {
            choose(rows(page.view.fields).first { it.titleView.text.toString() == activity.getString(R.string.config_music_stability) }, 3, true)
            page.view.tabs.buttons[2].performClick()
            val fields = rows(page.view.fields)
            assertFalse(fields.any { it.titleView.text.toString() == activity.getString(R.string.music_buffer) })
            val phone = fields.first { it.titleView.text.toString() == activity.getString(R.string.audio_buffer_phone_title) }
            choose(phone, 6, false)
            assertEquals(-1, GalaxyAudioBufferSettings.load(activity).phoneMillis)
            choose(phone, 2, true)
            choose(fields.first { it.titleView.text.toString() == activity.getString(R.string.audio_buffer_assistant_title) }, 1, true)
            choose(fields.first { it.titleView.text.toString() == activity.getString(R.string.audio_buffer_navigation_title) }, 4, true)
            assertEquals(original, repository.active())
            assertTrue(page.dirty)
            page.view.commitButton.performClick()
            assertFalse(page.dirty)
            val next = GalaxyAudioBufferSettings.load(GalaxyConfigurationContext(activity, repository.active()))
            assertEquals(0, next.mediaMillis)
            assertEquals(32, next.phoneMillis)
            assertEquals(0, next.assistantMillis)
            assertEquals(160, next.navigationMillis)
            assertEquals(300, GalaxyAudioBufferSettings.load(connected).mediaMillis)
            assertEquals(-1, GalaxyAudioBufferSettings.load(connected).phoneMillis)
        } finally { page.close() }
    }
}
