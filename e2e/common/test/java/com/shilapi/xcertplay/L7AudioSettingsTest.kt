package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
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
@Config(sdk = [30])
class L7AudioSettingsTest {
    private fun context() = Robolectric.buildActivity(Activity::class.java).setup().get().apply {
        setTheme(android.R.style.Theme_Material_Light_NoActionBar)
    }

    @Test fun builtinsHideTechnicalRowsAndCustomShowsThem() {
        val context = context()
        val parent = LinearLayout(context)
        for (mode in L7AudioTemplates.Mode.entries) {
            L7AudioTemplates.selectModel(context, if (mode == L7AudioTemplates.Mode.L6)
                L7AudioTemplates.Model.L6 else L7AudioTemplates.Model.L7)
            L7AudioTemplates.select(context, mode)
            parent.removeAllViews()
            L7AudioSettings.page(context, parent, {}, {}) { _, _, _, _ -> }
            val custom = mode == L7AudioTemplates.Mode.CUSTOM
            for (id in listOf(R.string.l7_audio_media, R.string.l7_audio_navigation, R.string.l7_audio_assistant,
                R.string.l7_audio_phone, R.string.l7_audio_bus, R.string.l7_template_edit,
                R.string.l7_template_import, R.string.l7_template_export))
                assertEquals("mode=$mode id=$id", custom, row(parent, context.getString(id)) != null)
            assertNotNull(row(parent, context.getString(R.string.music_buffer)))
            assertNotNull(row(parent, context.getString(R.string.l7_template_select)))
        }
    }

    @Test fun modelSelectionRequiresConfirmationAndL6RestoreKeepsItsModel() {
        val context = context()
        val parent = LinearLayout(context)
        L7AudioSettings.page(context, parent) { _, _, _, _ -> }
        fun chooseL6() {
            row(parent, context.getString(R.string.l7_template_model))!!.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val selector = ShadowAlertDialog.getLatestAlertDialog()
            selector.listView.performItemClick(selector.listView.adapter.getView(1, null, selector.listView), 1, 1L)
        }
        chooseL6()
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertEquals(L7AudioTemplates.Model.L7, L7AudioTemplates.model(context))
        chooseL6()
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(L7AudioTemplates.Mode.L6, L7AudioTemplates.mode(context))
        assertEquals(context.getString(R.string.l7_template_l6),
            row(parent, context.getString(R.string.l7_template_select))!!.valueView.text.toString())
        row(parent, context.getString(R.string.l7_template_select))!!.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val options = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals(2, options.listView.adapter.count)
        options.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        L7AudioTemplates.saveCustom(context, L7AudioTemplates.load(context).withChoice(
            com.shilapi.xcertplay.media.AudioOutputRole.NAVIGATION, 19))
        parent.removeAllViews(); L7AudioSettings.page(context, parent) { _, _, _, _ -> }
        row(parent, context.getString(R.string.l7_audio_restore))!!.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val restore = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(restore.findViewById<android.widget.TextView>(android.R.id.message).text.contains(
            context.getString(R.string.l7_template_model_l6)))
        restore.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(L7AudioTemplates.Mode.L6, L7AudioTemplates.mode(context))
        L7AudioTemplates.select(context, L7AudioTemplates.Mode.CUSTOM)
        assertEquals(19, L7AudioTemplates.load(context).choice(com.shilapi.xcertplay.media.AudioOutputRole.NAVIGATION))
    }

    @Test fun cancellingProfileSelectionPreservesModeAndVisibleRows() {
        val context = context()
        val parent = LinearLayout(context)
        L7AudioSettings.page(context, parent) { _, _, _, _ -> }
        row(parent, context.getString(R.string.l7_template_select))!!.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val selector = ShadowAlertDialog.getLatestAlertDialog()
        selector.listView.performItemClick(selector.listView.adapter.getView(2, null, selector.listView), 2, 2L)
        selector.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertEquals(L7AudioTemplates.Mode.L7, L7AudioTemplates.mode(context))
        assertNull(row(parent, context.getString(R.string.l7_template_edit)))
    }

    @Test fun confirmingProfileSelectionRefreshesAndHidesDetails() {
        val context = context()
        L7AudioTemplates.select(context, L7AudioTemplates.Mode.CUSTOM)
        val parent = LinearLayout(context)
        L7AudioSettings.page(context, parent) { _, _, _, _ -> }
        row(parent, context.getString(R.string.l7_template_select))!!.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val selector = ShadowAlertDialog.getLatestAlertDialog()
        selector.listView.performItemClick(selector.listView.adapter.getView(1, null, selector.listView), 1, 1L)
        selector.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(L7AudioTemplates.Mode.BUS, L7AudioTemplates.mode(context))
        assertNull(row(parent, context.getString(R.string.l7_audio_navigation)))
        assertEquals(context.getString(R.string.l7_template_bus),
            row(parent, context.getString(R.string.l7_template_select))!!.valueView.text.toString())
    }

    @Test fun restoringRequiresConfirmationAndPreservesCustomFile() {
        val context = context()
        AirPlayPersistence.saveMediaAudioChannel(context, 3)
        val parent = LinearLayout(context)
        L7AudioSettings.page(context, parent) { _, _, _, _ -> }
        fun restore() {
            row(parent, context.getString(R.string.l7_audio_restore))!!.performClick()
            shadowOf(Looper.getMainLooper()).idle()
        }
        restore()
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertEquals(3, L7AudioTemplates.load(context).choice(com.shilapi.xcertplay.media.AudioOutputRole.MEDIA))
        restore()
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(L7AudioTemplates.Mode.L7, L7AudioTemplates.mode(context))
        assertNull(row(parent, context.getString(R.string.l7_audio_media)))
        L7AudioTemplates.select(context, L7AudioTemplates.Mode.CUSTOM)
        assertEquals(3, L7AudioTemplates.load(context).choice(com.shilapi.xcertplay.media.AudioOutputRole.MEDIA))
    }

    private fun row(view: View, label: String): L7SettingRow? {
        if (view is L7SettingRow && view.titleView.text.toString() == label) return view
        if (view is ViewGroup) for (index in 0 until view.childCount)
            row(view.getChildAt(index), label)?.let { return it }
        return null
    }
}
