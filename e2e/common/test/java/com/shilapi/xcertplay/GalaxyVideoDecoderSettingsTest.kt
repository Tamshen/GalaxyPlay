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

    @Test fun unsupportedVehicleShowsReadOnlyDefaultInDisplayPage() {
        val activity = activity()
        L7AudioTemplates.selectModel(activity, L7AudioTemplates.Model.CUSTOM)
        val parent = LinearLayout(activity)
        L7DisplaySettings.add(activity, parent)
        val row = rows(parent).single { it.titleView.text == activity.getString(R.string.galaxy_video_decoder_title) }
        assertEquals(activity.getString(R.string.galaxy_video_decoder_default), row.valueView.text.toString())
        assertFalse(row.hasOnClickListeners())
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

    private fun rows(view: View): List<L7SettingRow> = when (view) {
        is L7SettingRow -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { rows(view.getChildAt(it)) }
        else -> emptyList()
    }
}
