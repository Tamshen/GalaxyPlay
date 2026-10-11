package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
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
@Config(sdk = [29, 30])
class GalaxyVideoDecoderSettingsTest {
    private val c2 = GalaxyVideoDecoderPreferences.Mode.C2
    private val omx = GalaxyVideoDecoderPreferences.Mode.OMX
    private val default = GalaxyVideoDecoderPreferences.Mode.DEFAULT
    private val available = setOf(c2.decoder!!, omx.decoder!!)
    private fun activity() = Robolectric.buildActivity(Activity::class.java).setup().get().apply {
        setTheme(android.R.style.Theme_Material_Light_NoActionBar)
    }

    @Test fun knownModelsDefaultToC2AndSaveChoicesIndependently() {
        val activity = activity()
        L7AudioTemplates.selectModel(activity, L7AudioTemplates.Model.L7)
        assertEquals(c2, GalaxyVideoDecoderPreferences.load(activity, available))
        GalaxyVideoDecoderPreferences.save(activity, omx, L7AudioTemplates.Model.L7)
        L7AudioTemplates.selectModel(activity, L7AudioTemplates.Model.L6)
        assertEquals(c2, GalaxyVideoDecoderPreferences.load(activity, available))
        GalaxyVideoDecoderPreferences.save(activity, default, L7AudioTemplates.Model.L6)
        L7AudioTemplates.selectModel(activity, L7AudioTemplates.Model.L7)
        assertEquals(omx, GalaxyVideoDecoderPreferences.load(activity, available))
        L7AudioTemplates.selectModel(activity, L7AudioTemplates.Model.L6)
        assertEquals(default, GalaxyVideoDecoderPreferences.load(activity, available))
    }

    @Test fun unavailablePreferredDecoderFallsBackWithoutErasingItsPreference() {
        val activity = activity()
        L7AudioTemplates.selectModel(activity, L7AudioTemplates.Model.L7)
        GalaxyVideoDecoderPreferences.save(activity, omx, L7AudioTemplates.Model.L7)
        assertEquals(default, GalaxyVideoDecoderPreferences.load(activity, setOf(c2.decoder!!)))
        assertEquals(omx, GalaxyVideoDecoderPreferences.load(activity, available))
        assertEquals(default, GalaxyVideoDecoderPreferences.load(activity, emptySet()))
    }

    @Test fun unknownAndCustomVehiclesKeepDefaultEvenWithMatchingHardwareNames() {
        val activity = activity()
        assertFalse(GalaxyVideoDecoderPreferences.supported(activity))
        assertEquals(default, GalaxyVideoDecoderPreferences.load(activity, available))
        L7AudioTemplates.selectModel(activity, L7AudioTemplates.Model.CUSTOM)
        activity.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit()
            .putString("galaxy_video_decoder_custom", c2.name).commit()
        assertEquals(listOf(default), GalaxyVideoDecoderPreferences.options(activity, available))
        assertEquals(default, GalaxyVideoDecoderPreferences.load(activity, available))
    }

    @Test fun cancelKeepsC2AndConfirmationAppliesOmx() {
        val activity = activity()
        L7AudioTemplates.selectModel(activity, L7AudioTemplates.Model.L7)
        val parent = LinearLayout(activity)
        GalaxyVideoDecoderSettings.add(activity, parent, available)
        val row = rows(parent).single()
        assertEquals(activity.getString(R.string.galaxy_video_decoder_c2), row.valueView.text.toString())
        row.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        var dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals(3, dialog.listView.adapter.count)
        dialog.listView.performItemClick(dialog.listView.adapter.getView(2, null, dialog.listView), 2, 2L)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertEquals(c2, GalaxyVideoDecoderPreferences.load(activity, available))
        row.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        dialog = ShadowAlertDialog.getLatestAlertDialog()
        dialog.listView.performItemClick(dialog.listView.adapter.getView(2, null, dialog.listView), 2, 2L)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(omx, GalaxyVideoDecoderPreferences.load(activity, available))
        assertEquals(activity.getString(R.string.galaxy_video_decoder_omx), row.valueView.text.toString())
    }

    @Test fun unsupportedVehicleCanOpenChoicesButOnlyDefaultIsEnabled() {
        val activity = activity()
        L7AudioTemplates.selectModel(activity, L7AudioTemplates.Model.CUSTOM)
        val parent = LinearLayout(activity)
        L7DisplaySettings.add(activity, parent)
        val row = rows(parent).single { it.titleView.text == activity.getString(R.string.galaxy_video_decoder_title) }
        assertEquals(activity.getString(R.string.galaxy_video_decoder_default), row.valueView.text.toString())
        assertTrue(row.hasOnClickListeners())
        row.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals(3, dialog.listView.adapter.count)
        assertTrue(dialog.listView.adapter.isEnabled(0))
        for (index in 1..2) {
            assertFalse(dialog.listView.adapter.isEnabled(index))
            val option = dialog.listView.adapter.getView(index, null, dialog.listView)
            assertFalse(option.isEnabled)
            dialog.listView.performItemClick(option, index, index.toLong())
            assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(dialog.isShowing)
        assertEquals(default, GalaxyVideoDecoderPreferences.load(activity, available))
        dialog.dismiss()
    }

    @Test fun missingCodecRemainsVisibleButCannotChangePendingSelection() {
        val activity = activity()
        L7AudioTemplates.selectModel(activity, L7AudioTemplates.Model.L7)
        val parent = LinearLayout(activity)
        val onlyOmx = setOf(omx.decoder!!)
        GalaxyVideoDecoderSettings.add(activity, parent, onlyOmx)
        rows(parent).single().performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val list = dialog.listView
        assertEquals(3, list.adapter.count)
        assertFalse(list.adapter.isEnabled(1))
        assertTrue(list.adapter.isEnabled(2))
        list.performItemClick(list.adapter.getView(2, null, list), 2, 2L)
        list.performItemClick(list.adapter.getView(1, null, list), 1, 1L)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(omx, GalaxyVideoDecoderPreferences.load(activity, onlyOmx))
    }

    @Test fun noHardwareStillOpensSelectorAndDoesNotEraseStoredPreference() {
        val activity = activity()
        L7AudioTemplates.selectModel(activity, L7AudioTemplates.Model.L7)
        GalaxyVideoDecoderPreferences.save(activity, c2, L7AudioTemplates.Model.L7)
        val parent = LinearLayout(activity)
        GalaxyVideoDecoderSettings.add(activity, parent, emptySet())
        rows(parent).single().performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals(0, dialog.listView.checkedItemPosition)
        assertFalse(dialog.listView.adapter.isEnabled(1))
        assertFalse(dialog.listView.adapter.isEnabled(2))
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertEquals(c2, GalaxyVideoDecoderPreferences.load(activity, available))
    }

    @Test fun modelChangeWhileSelectorIsOpenCannotWriteOldChoiceIntoNewModel() {
        val activity = activity()
        L7AudioTemplates.selectModel(activity, L7AudioTemplates.Model.L7)
        val parent = LinearLayout(activity)
        GalaxyVideoDecoderSettings.add(activity, parent, available)
        rows(parent).single().performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        dialog.listView.performItemClick(dialog.listView.adapter.getView(2, null, dialog.listView), 2, 2L)
        L7AudioTemplates.selectModel(activity, L7AudioTemplates.Model.L6)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(c2, GalaxyVideoDecoderPreferences.load(activity, available))
        L7AudioTemplates.selectModel(activity, L7AudioTemplates.Model.L7)
        assertEquals(c2, GalaxyVideoDecoderPreferences.load(activity, available))
    }

    @Test fun confirmationOnlyUpdatesPreferenceDraftUntilThePageIsSaved() {
        val activity = activity()
        val repository = GalaxyProfiles(activity)
        val original = repository.applyTemplate("l7")
        val draft = GalaxyConfigurationContext(activity, original, editable = true)
        val parent = LinearLayout(draft)
        GalaxyVideoDecoderSettings.add(draft, parent, available)
        rows(parent).single().performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals(activity.getString(R.string.profile_update_draft), dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        dialog.listView.performItemClick(dialog.listView.adapter.getView(2, null, dialog.listView), 2, 2L)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(omx, GalaxyVideoDecoderPreferences.load(draft, available))
        assertEquals(c2, GalaxyVideoDecoderPreferences.load(activity, available))
        assertEquals(original.configuration, repository.active().configuration)
        repository.save(original.copy(configuration = draft.configuration()))
        assertEquals(omx, GalaxyVideoDecoderPreferences.load(activity, available))
    }

    private fun rows(view: View): List<L7SettingRow> = when (view) {
        is L7SettingRow -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { rows(view.getChildAt(it)) }
        else -> emptyList()
    }
}
