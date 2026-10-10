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
            .first { it.titleView.text.toString() == activity.getString(R.string.config_smoothness) }.performClick()
        val picker = dialog()
        assertEquals(activity.getString(R.string.profile_update_draft), picker.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        picker.listView.performItemClick(picker.listView.adapter.getView(1, null, picker.listView), 1, 1)
        picker.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
    }
    private fun template(page: GalaxyConfigurationPage, model: String) {
        page.view.vehicleButton.performClick()
        val picker = dialog()
        val index = GalaxyVehicleTemplates.entries.indexOfFirst { it.id == model }
        picker.listView.performItemClick(picker.listView.adapter.getView(index, null, picker.listView), index, index.toLong())
        picker.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
    }
    @Test fun confirmedVehicleTemplateOverwritesOneCurrentFileWithoutASeparateUseStep() {
        val activity = activity()
        val repository = GalaxyProfiles(activity)
        val before = repository.active()
        val page = GalaxyConfigurationPage(activity) {}
        template(page, "l6")
        val l6 = repository.active()
        assertEquals(before.id, l6.id)
        assertEquals(before.revision + 1, l6.revision)
        assertEquals("l6", l6.model)
        assertEquals(1, repository.list().size)
        assertFalse(page.dirty)
        assertFalse(page.view.commitButton.isEnabled)
        template(page, "l7")
        assertEquals("l7", repository.active().model)
        assertEquals(l6.id, repository.active().id)
        assertEquals(l6.revision + 1, repository.active().revision)
        page.close()
    }
    @Test fun editsAfterApplyingTemplateSaveToTheSameCompleteConfigurationExactlyOnce() {
        val activity = activity()
        val repository = GalaxyProfiles(activity)
        val page = GalaxyConfigurationPage(activity) {}
        template(page, "l6")
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
        template(page, "l6")
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
        for (model in listOf("l6", "l7")) {
            template(page, model)
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
    @Test fun choosingOrCancellingVehicleKeepsTheFileAndDirtyDraftUntilExplicitApply() {
        val activity = activity()
        val repository = GalaxyProfiles(activity)
        val page = GalaxyConfigurationPage(activity) {}
        val original = repository.active()
        modify(page, activity)
        val draft = state(activity).draft
        page.view.vehicleButton.performClick()
        val picker = dialog()
        picker.listView.performItemClick(picker.listView.adapter.getView(1, null, picker.listView), 1, 1)
        assertEquals(original, repository.active())
        assertEquals(draft, state(activity).draft)
        picker.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertTrue(page.dirty)
        assertEquals(draft, state(activity).draft)
        assertEquals(original, repository.active())
        template(page, "l7") // 同车型也可重新应用默认值。
        assertFalse(page.dirty)
        assertEquals(30, repository.active().configuration.preferences["xcertplay_airplay"]!!["display_fps"])
        page.close()
    }
    @Test fun repeatedOpeningUsesOneDialogAndClosedPageRejectsLateApply() {
        val activity = activity()
        val repository = GalaxyProfiles(activity)
        val page = GalaxyConfigurationPage(activity) {}
        val original = repository.active()
        page.view.vehicleButton.performClick()
        val picker = dialog()
        page.view.vehicleButton.performClick()
        assertSame(picker, dialog())
        page.close()
        assertFalse(picker.isShowing)
        picker.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        page.view.vehicleButton.performClick()
        assertEquals(original, repository.active())
    }
    @Test fun tabsStayInOneScrollableRowAndRevealTheSelectedCategory() {
        val activity = activity()
        val page = GalaxyConfigurationPage(activity) {}
        activity.setContentView(page.view)
        page.view.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY))
        page.view.layout(0, 0, 320, 1000)
        val tabs = page.view.tabs
        assertTrue(tabs.getChildAt(0).width > tabs.width)
        val tops = tabs.buttons.map { it.top }.distinct()
        assertEquals(1, tops.size)
        assertEquals(1, tabs.buttons.map { it.width }.distinct().size)
        assertTrue(tabs.buttons.zipWithNext().all { (left, right) -> left.right == right.left })
        assertEquals(activity.getColor(R.color.product_ui_primary_text), tabs.buttons.first().currentTextColor)
        assertTrue(tabs.buttons.all { it.compoundDrawablesRelative.all { drawable -> drawable == null } })
        tabs.buttons.last().performClick()
        shadowOf(Looper.getMainLooper()).idle()
        // 滚动动画最终将最右侧分类带入可见范围。
        tabs.computeScroll()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        tabs.computeScroll()
        assertTrue(tabs.buttons.last().isSelected)
        L7Ui.refresh(tabs)
        assertEquals(activity.getColor(R.color.product_ui_primary_text), tabs.buttons.last().currentTextColor)
        assertEquals(activity.getColor(R.color.product_ui_text), tabs.buttons.first().currentTextColor)
        assertEquals(4, state(activity).group)
        assertTrue(tabs.scrollX > 0)
        assertTrue(tabs.buttons.last().right <= tabs.scrollX + tabs.width)
        page.close()
    }
    @Test fun connectionUsesOneHotspotEditorAndNeverShowsBandChannelOrSecurityEvenWhenExpanded() {
        val activity = activity()
        val page = GalaxyConfigurationPage(activity) {}
        page.view.tabs.buttons[1].performClick()
        fun titles() = descendants(page.view.fields).filterIsInstance<L7SettingRow>().map { it.titleView.text.toString() }
        assertTrue(activity.getString(R.string.config_hotspot_title) in titles())
        for (title in listOf(R.string.profile_hotspot_name, R.string.profile_hotspot_password,
            R.string.profile_hotspot_band, R.string.profile_hotspot_channel, R.string.profile_hotspot_security))
            assertFalse(activity.getString(title) in titles())
        descendants(page.view.fields).filterIsInstance<L7SettingRow>()
            .first { it.titleView.text.toString() == activity.getString(R.string.config_advanced_open) }.performClick()
        assertTrue(activity.getString(R.string.report_location_to_iphone) in titles())
        assertFalse(activity.getString(R.string.profile_hotspot_band) in titles())
        page.close()
    }
    @Test fun advancedSettingsStartCollapsedAndKeepDraftAndExpansionAcrossPageRebuild() {
        val activity = activity()
        val repository = GalaxyProfiles(activity)
        val original = repository.active()
        val page = GalaxyConfigurationPage(activity) {}
        page.view.tabs.buttons[3].performClick()
        fun rows() = descendants(page.view.fields).filterIsInstance<L7SettingRow>()
        assertFalse(rows().any { it.titleView.text.toString() == activity.getString(R.string.profile_software_hevc) })
        rows().first { it.titleView.text.toString() == activity.getString(R.string.config_advanced_open) }.performClick()
        rows().first { it.titleView.text.toString() == activity.getString(R.string.profile_software_hevc) }.performClick()
        assertTrue(page.dirty)
        assertEquals(original, repository.active())
        page.close()
        val rebuilt = GalaxyConfigurationPage(activity) {}
        assertTrue(rebuilt.dirty)
        assertTrue(3 in state(activity).advanced)
        assertTrue(descendants(rebuilt.view.fields).filterIsInstance<L7SettingRow>()
            .any { it.titleView.text.toString() == activity.getString(R.string.profile_software_hevc) })
        rebuilt.close()
    }
    @Test fun hotspotPairOnlyUpdatesDraftAndAutomaticallyDerivesSecurityAndNetworkDefaults() {
        val activity = activity()
        val repository = GalaxyProfiles(activity)
        val original = repository.active()
        val page = GalaxyConfigurationPage(activity) {}
        page.view.tabs.buttons[1].performClick()
        val hotspot = descendants(page.view.fields).filterIsInstance<L7SettingRow>()
            .first { it.titleView.text.toString() == activity.getString(R.string.config_hotspot_title) }
        hotspot.performClick()
        dialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertFalse(page.dirty)
        hotspot.performClick()
        val picker = dialog()
        val inputs = descendants(picker.window!!.decorView).filterIsInstance<android.widget.EditText>()
        inputs[0].setText("test-hotspot"); inputs[1].setText("test-secret")
        picker.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(page.dirty)
        assertEquals(original, repository.active())
        page.view.commitButton.performClick()
        val preferences = repository.active().configuration.preferences.getValue("xcertplay_airplay")
        assertEquals("test-hotspot", preferences["manual_hotspot_ssid"])
        assertEquals("test-secret", preferences["manual_hotspot_passphrase"])
        assertEquals("WPA2", preferences["manual_hotspot_security"])
        assertEquals("AUTO", preferences["manual_hotspot_band"])
        assertEquals(0, preferences["manual_hotspot_channel"])
        page.close()
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
