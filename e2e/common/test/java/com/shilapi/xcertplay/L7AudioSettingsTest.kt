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

    @Test fun legacyModesOfferDirectEditingWithoutASeparateProfileSelector() {
        val context = context()
        val parent = LinearLayout(context)
        for (mode in L7AudioTemplates.Mode.entries) {
            L7AudioTemplates.selectModel(context, when (mode) {
                L7AudioTemplates.Mode.L6 -> L7AudioTemplates.Model.L6
                L7AudioTemplates.Mode.SYSTEM -> L7AudioTemplates.Model.CUSTOM
                else -> L7AudioTemplates.Model.L7
            })
            L7AudioTemplates.select(context, mode)
            parent.removeAllViews()
            L7AudioSettings.page(context, parent) { _, _, _, _ -> }
            for (id in listOf(R.string.l7_audio_media, R.string.l7_audio_navigation, R.string.l7_audio_assistant,
                R.string.l7_audio_phone))
                assertNotNull("mode=$mode id=$id", row(parent, context.getString(id)))
            assertNotNull(row(parent, context.getString(R.string.l7_audio_bus)))
            for (id in listOf(R.string.l7_template_edit,
                R.string.l7_template_import, R.string.l7_template_export))
                assertNull("mode=$mode id=$id", row(parent, context.getString(id)))
            assertNull(row(parent, context.getString(R.string.l7_template_model)))
            assertNull(row(parent, context.getString(R.string.l7_template_detect)))
            assertNotNull(row(parent, context.getString(R.string.music_buffer)))
            assertNull(row(parent, context.getString(R.string.l7_template_select)))
            assertEquals(mode, L7AudioTemplates.mode(context))
        }
    }

    @Test fun l6ProfileRestoreKeepsItsModelAndCustomFile() {
        val context = context()
        val parent = LinearLayout(context)
        L7AudioTemplates.selectModel(context, L7AudioTemplates.Model.L6)
        L7AudioSettings.page(context, parent) { _, _, _, _ -> }
        assertEquals(L7AudioTemplates.Mode.L6, L7AudioTemplates.mode(context))
        L7AudioTemplates.saveCustom(context, L7AudioTemplates.load(context).withChoice(
            com.shilapi.xcertplay.media.AudioOutputRole.NAVIGATION, 19))
        parent.removeAllViews(); L7AudioSettings.page(context, parent) { _, _, _, _ -> }
        row(parent, context.getString(R.string.l7_audio_restore))!!.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val restore = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals(context.getString(R.string.l7_template_restore_confirm),
            restore.findViewById<android.widget.TextView>(android.R.id.message).text.toString())
        restore.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(L7AudioTemplates.Mode.L6, L7AudioTemplates.mode(context))
        L7AudioTemplates.select(context, L7AudioTemplates.Mode.CUSTOM)
        assertEquals(19, L7AudioTemplates.load(context).choice(com.shilapi.xcertplay.media.AudioOutputRole.NAVIGATION))
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
        assertNotNull(row(parent, context.getString(R.string.l7_audio_media)))
        L7AudioTemplates.select(context, L7AudioTemplates.Mode.CUSTOM)
        assertEquals(3, L7AudioTemplates.load(context).choice(com.shilapi.xcertplay.media.AudioOutputRole.MEDIA))
    }

    @Test fun customVehicleRestoresSystemDefaultsWithoutFileToolsOrDeletingItsFile() {
        val context = context()
        L7AudioTemplates.selectModel(context, L7AudioTemplates.Model.CUSTOM)
        L7AudioTemplates.saveCustom(context, L7AudioTemplates.load(context).withChoice(
            com.shilapi.xcertplay.media.AudioOutputRole.MEDIA, 18))
        val file = java.io.File(context.filesDir, "audio-template-custom.json")
        val previous = file.readText()
        val parent = LinearLayout(context)
        L7AudioSettings.page(context, parent) { _, _, _, _ -> }
        assertNull(row(parent, context.getString(R.string.l7_template_edit)))
        row(parent, context.getString(R.string.l7_audio_restore))!!.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(L7AudioTemplates.Model.CUSTOM, L7AudioTemplates.model(context))
        assertEquals(L7AudioTemplates.Mode.SYSTEM, L7AudioTemplates.mode(context))
        assertNull(row(parent, context.getString(R.string.l7_template_select)))
        assertEquals(previous, file.readText())
    }

    @Test fun missingBusAddressesCannotEnableBusOrChangeConfiguration() {
        val context = context()
        for (model in listOf(L7AudioTemplates.Model.L6, L7AudioTemplates.Model.CUSTOM)) {
            L7AudioTemplates.selectModel(context, model)
            val before = L7AudioTemplates.load(context).toJson()
            val mode = L7AudioTemplates.mode(context)
            val parent = LinearLayout(context)
            L7AudioSettings.page(context, parent) { _, _, _, _ -> }
            val bus = row(parent, context.getString(R.string.l7_audio_bus))!!
            assertFalse(bus.isEnabled)
            assertEquals(context.getString(R.string.l7_template_bus_unconfigured), bus.descriptionView.text.toString())
            bus.performClick()
            assertEquals(before, L7AudioTemplates.load(context).toJson())
            assertEquals(mode, L7AudioTemplates.mode(context))
        }
    }

    @Test fun enablingBusFromBuiltinKeepsVehicleAndChannelChoices() {
        val context = context()
        L7AudioTemplates.selectModel(context, L7AudioTemplates.Model.L7)
        val before = L7AudioTemplates.load(context)
        val parent = LinearLayout(context)
        L7AudioSettings.page(context, parent) { _, _, _, _ -> }
        row(parent, context.getString(R.string.l7_audio_bus))!!.performClick()
        assertEquals(L7AudioTemplates.Model.L7, L7AudioTemplates.model(context))
        assertEquals(L7AudioTemplates.Mode.CUSTOM, L7AudioTemplates.mode(context))
        val after = L7AudioTemplates.load(context)
        assertTrue(after.preferBus)
        for (role in com.shilapi.xcertplay.media.AudioOutputRole.entries)
            assertEquals(before.choice(role), after.choice(role))
    }

    @Test fun editingPhoneFromBuiltinDerivesCustomAndLeavesOtherRoutesUnchanged() {
        val context = context()
        val parent = LinearLayout(context)
        val before = L7AudioTemplates.load(context)
        var selectedRole: com.shilapi.xcertplay.media.AudioOutputRole? = null
        L7AudioSettings.page(context, parent) { _, _, role, apply ->
            selectedRole = role
            apply(com.shilapi.xcertplay.media.AudioOutputPolicy.NAVIGATION)
        }
        row(parent, context.getString(R.string.l7_audio_phone))!!.performClick()
        assertEquals(com.shilapi.xcertplay.media.AudioOutputRole.PHONE, selectedRole)
        assertEquals(L7AudioTemplates.Mode.CUSTOM, L7AudioTemplates.mode(context))
        assertEquals(103, L7AudioTemplates.load(context).choice(com.shilapi.xcertplay.media.AudioOutputRole.PHONE))
        assertEquals(before.choice(com.shilapi.xcertplay.media.AudioOutputRole.MEDIA),
            L7AudioTemplates.load(context).choice(com.shilapi.xcertplay.media.AudioOutputRole.MEDIA))
        assertNull(row(parent, context.getString(R.string.l7_template_edit)))
    }

    private fun row(view: View, label: String): L7SettingRow? {
        if (view is L7SettingRow && view.titleView.text.toString() == label) return view
        if (view is ViewGroup) for (index in 0 until view.childCount)
            row(view.getChildAt(index), label)?.let { return it }
        return null
    }
}
