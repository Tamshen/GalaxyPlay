package com.shilapi.xcertplay

import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
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
class GalaxyConfigurationPageTest {
    private fun activity() = Robolectric.buildActivity(ComponentActivity::class.java).setup().get().apply {
        setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        java.io.File(filesDir, "configurations").deleteRecursively()
    }
    private fun state(activity: ComponentActivity) = ViewModelProvider(activity)[GalaxyConfigurationState::class.java]
    private fun dialog(): AlertDialog {
        shadowOf(Looper.getMainLooper()).idle()
        return ShadowAlertDialog.getLatestAlertDialog()
    }
    private fun modify(page: GalaxyConfigurationPage, activity: ComponentActivity) {
        descendants(page.view.fields).filterIsInstance<L7SettingRow>()
            .first { it.titleView.text.toString() == activity.getString(R.string.frame_rate) }.performClick()
        val picker = dialog()
        picker.listView.performItemClick(picker.listView.adapter.getView(1, null, picker.listView), 1, 1)
        picker.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
    }
    @Test fun modelButtonsImmediatelyOverwriteOneCurrentFileWithoutASeparateUseStep() {
        val activity = activity()
        val repository = GalaxyProfiles(activity)
        val before = repository.active()
        val page = GalaxyConfigurationPage(activity) {}
        page.view.l6.performClick()
        val l6 = repository.active()
        assertEquals(before.id, l6.id)
        assertEquals(before.revision + 1, l6.revision)
        assertEquals("l6", l6.model)
        assertEquals(1, repository.list().size)
        assertFalse(page.dirty)
        assertFalse(page.view.commitButton.isEnabled)
        page.view.l7.performClick()
        assertEquals("l7", repository.active().model)
        assertEquals(l6.id, repository.active().id)
        assertEquals(l6.revision + 1, repository.active().revision)
        page.close()
    }
    @Test fun editsAfterApplyingTemplateSaveToTheSameCompleteConfigurationExactlyOnce() {
        val activity = activity()
        val repository = GalaxyProfiles(activity)
        val page = GalaxyConfigurationPage(activity) {}
        page.view.l6.performClick()
        val applied = repository.active()
        modify(page, activity)
        assertEquals(applied, repository.active())
        assertTrue(page.dirty)
        page.view.commitButton.performClick()
        val saved = repository.active()
        assertEquals(applied.id, saved.id)
        assertEquals("l6", saved.model)
        assertEquals(applied.revision + 1, saved.revision)
        assertEquals(60, saved.configuration.preferences["xcertplay_airplay"]!!["display_fps"])
        page.view.commitButton.performClick()
        assertEquals(saved, repository.active())
        assertEquals(1, repository.list().size)
        page.close()
    }
    @Test fun clickingTemplateAlsoReplacesUnsavedDraftAndFrozenConnectionStillReadsOldSettings() {
        val activity = activity()
        val repository = GalaxyProfiles(activity)
        val frozen = GalaxyConfigurationContext(activity, repository.applyTemplate("l7"), runtimeOnly = true)
        val page = GalaxyConfigurationPage(activity) {}
        modify(page, activity)
        page.view.l6.performClick()
        assertFalse(page.dirty)
        assertEquals(30, repository.active().configuration.preferences["xcertplay_airplay"]!!["display_fps"])
        assertEquals(L7AudioTemplates.Model.L6, L7AudioTemplates.model(activity))
        assertEquals(L7AudioTemplates.Model.L7, L7AudioTemplates.model(frozen))
        page.close()
    }
    @Test fun switchingCategoryIsImmediateAndDoesNotWriteUnsavedParameters() {
        val activity = activity()
        val repository = GalaxyProfiles(activity)
        val original = repository.active()
        val page = GalaxyConfigurationPage(activity) {}
        modify(page, activity)
        descendants(page.view).filterIsInstance<android.widget.Button>()
            .first { it.text.toString() == activity.getString(R.string.config_page_video) }.performClick()
        assertEquals(3, state(activity).group)
        assertTrue(page.dirty)
        assertEquals(original, repository.active())
        assertEquals(60, state(activity).draft!!.configuration.preferences["xcertplay_airplay"]!!["display_fps"])
        page.close()
    }
    @Test fun leavingDirtyConfigurationCanContinueEditingOrDiscardBeforeNavigation() {
        val activity = activity()
        val original = GalaxyProfiles(activity).active()
        val page = GalaxyConfigurationPage(activity) {}
        modify(page, activity)
        var left = false
        page.requestLeave { left = true }
        dialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertFalse(left); assertTrue(page.dirty)
        page.requestLeave { left = true }
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(left); assertFalse(page.dirty)
        assertEquals(original, GalaxyProfiles(activity).active())
        page.close()
    }
    @Test fun pageRebuildKeepsDraftAndCategoryWithoutSaving() {
        val activity = activity()
        val repository = GalaxyProfiles(activity)
        val original = repository.active()
        val page = GalaxyConfigurationPage(activity) {}
        modify(page, activity)
        state(activity).group = 3
        page.close()
        val rebuilt = GalaxyConfigurationPage(activity) {}
        assertTrue(rebuilt.dirty)
        assertEquals(3, state(activity).group)
        assertEquals(original, repository.active())
        rebuilt.close()
    }
    @Test fun configurationPageHasNoLanguageOrDensityControlsAndTemplateKeepsBoth() {
        val activity = activity()
        AppLocale.save(activity, "en"); L7UiDensity.save(activity, 320)
        val page = GalaxyConfigurationPage(activity) {}
        assertFalse(descendants(page.view).filterIsInstance<android.widget.Button>().any {
            it.text.toString() == activity.getString(R.string.config_page_interface)
        })
        for (button in listOf(page.view.l6, page.view.l7)) {
            button.performClick()
            assertEquals("en", AppLocale.preference(activity))
            assertEquals(320, L7UiDensity.value(activity))
        }
        page.close()
    }
    @Test fun applicationPageOffersLanguageAndNativeSizePickerWithCancelKeepingOldValue() {
        val activity = activity()
        L7UiDensity.save(activity, 280)
        val parent = android.widget.LinearLayout(activity)
        GalaxyApplicationSettingsPage.add(activity, parent)
        val rows = descendants(parent).filterIsInstance<L7SettingRow>()
        assertTrue(rows.any { it.titleView.text.toString() == activity.getString(R.string.language_app_language) })
        rows.first { it.titleView.text.toString() == activity.getString(R.string.l7_ui_size) }.performClick()
        dialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertEquals(280, L7UiDensity.value(activity))
        assertFalse(GalaxyProfiles(activity).active().configuration.preferences.getValue("l7_ui").containsKey("density"))
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
