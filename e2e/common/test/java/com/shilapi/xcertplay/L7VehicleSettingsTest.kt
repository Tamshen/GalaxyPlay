package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.os.Build
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
import org.robolectric.shadows.ShadowToast
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class L7VehicleSettingsTest {
    private fun context() = Robolectric.buildActivity(Activity::class.java).setup().get().apply {
        setTheme(android.R.style.Theme_Material_Light_NoActionBar)
    }

    @Test fun manualSelectionCancelsWithoutSavingAndConfirmationRefreshesVehicleOnly() {
        val context = context()
        val parent = LinearLayout(context)
        L7VehicleSettings.page(context, parent)
        fun chooseL6() {
            row(parent, context.getString(R.string.l7_template_model))!!.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val selector = ShadowAlertDialog.getLatestAlertDialog()
            assertEquals(3, selector.listView.adapter.count)
            selector.listView.performItemClick(selector.listView.adapter.getView(1, null, selector.listView), 1, 1L)
        }
        chooseL6()
        click(AlertDialog.BUTTON_NEGATIVE)
        assertEquals(L7AudioTemplates.Model.L7, L7AudioTemplates.model(context))
        assertEquals(context.getString(R.string.l7_template_model_l7),
            row(parent, context.getString(R.string.l7_template_model))!!.valueView.text.toString())
        chooseL6()
        click(AlertDialog.BUTTON_POSITIVE)
        assertEquals(L7AudioTemplates.Model.L6, L7AudioTemplates.model(context))
        assertEquals(L7AudioTemplates.Mode.L6, L7AudioTemplates.mode(context))
        assertEquals(context.getString(R.string.l7_template_model_l6),
            row(parent, context.getString(R.string.l7_template_model))!!.valueView.text.toString())
        assertNull(row(parent, context.getString(R.string.l7_template_select)))
    }

    @Test fun detectedModelRequiresConfirmationAndCanBeReviewedAfterRetainingSelection() {
        val context = context()
        buildIdentity("g733")
        val parent = LinearLayout(context)
        L7VehicleSettings.page(context, parent)
        fun detect() {
            row(parent, context.getString(R.string.l7_template_detect))!!.performClick()
            shadowOf(Looper.getMainLooper()).idle()
        }
        detect()
        assertEquals(L7AudioTemplates.Model.L7, L7AudioTemplates.model(context))
        click(AlertDialog.BUTTON_NEGATIVE)
        assertEquals(L7AudioTemplates.Model.L7, L7AudioTemplates.model(context))
        detect()
        click(AlertDialog.BUTTON_POSITIVE)
        assertEquals(L7AudioTemplates.Model.L6, L7AudioTemplates.model(context))
        assertEquals(context.getString(R.string.l7_template_model_l6),
            row(parent, context.getString(R.string.l7_template_model))!!.valueView.text.toString())
    }

    @Test fun unknownDetectionShowsManualFallbackAndPreservesSelectedModel() {
        val context = context()
        buildIdentity("unknown")
        L7AudioTemplates.selectModel(context, L7AudioTemplates.Model.L6)
        val parent = LinearLayout(context)
        L7VehicleSettings.page(context, parent)
        row(parent, context.getString(R.string.l7_template_detect))!!.performClick()
        assertEquals(context.getString(R.string.l7_template_detect_unknown), ShadowToast.getTextOfLatestToast())
        assertEquals(L7AudioTemplates.Model.L6, L7AudioTemplates.model(context))
        assertNotNull(row(parent, context.getString(R.string.l7_template_model)))
    }

    @Test fun thirdModelCancelsWithoutCreatingFileAndConfirmedChoiceShowsIndependentProfile() {
        val context = context()
        val parent = LinearLayout(context)
        L7VehicleSettings.page(context, parent)
        fun chooseCustom() {
            row(parent, context.getString(R.string.l7_template_model))!!.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val selector = ShadowAlertDialog.getLatestAlertDialog()
            assertEquals(context.getString(R.string.l7_template_model_custom), selector.listView.adapter.getItem(2))
            selector.listView.performItemClick(selector.listView.adapter.getView(2, null, selector.listView), 2, 2L)
        }
        chooseCustom(); click(AlertDialog.BUTTON_NEGATIVE)
        assertEquals(L7AudioTemplates.Model.L7, L7AudioTemplates.model(context))
        assertFalse(java.io.File(context.filesDir, "audio-template-custom.json").exists())
        chooseCustom(); click(AlertDialog.BUTTON_POSITIVE)
        assertEquals(L7AudioTemplates.Model.CUSTOM, L7AudioTemplates.model(context))
        assertEquals(context.getString(R.string.l7_template_model_custom),
            row(parent, context.getString(R.string.l7_template_model))!!.valueView.text.toString())
        assertEquals(context.getString(R.string.l7_template_custom),
            row(parent, context.getString(R.string.l7_vehicle_profile))!!.valueView.text.toString())
        row(parent, context.getString(R.string.l7_template_model))!!.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val selector = ShadowAlertDialog.getLatestAlertDialog()
        selector.listView.performItemClick(selector.listView.adapter.getView(1, null, selector.listView), 1, 1L)
        click(AlertDialog.BUTTON_POSITIVE)
        assertEquals(context.getString(R.string.l7_template_l6),
            row(parent, context.getString(R.string.l7_vehicle_profile))!!.valueView.text.toString())
    }

    private fun buildIdentity(device: String) {
        ReflectionHelpers.setStaticField(Build::class.java, "DEVICE", device)
        ReflectionHelpers.setStaticField(Build::class.java, "PRODUCT", "unknown")
        ReflectionHelpers.setStaticField(Build::class.java, "MODEL", "unknown")
    }

    private fun click(which: Int) {
        ShadowAlertDialog.getLatestAlertDialog().getButton(which).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun row(view: View, label: String): L7SettingRow? {
        if (view is L7SettingRow && view.titleView.text.toString() == label) return view
        if (view is ViewGroup) for (index in 0 until view.childCount)
            row(view.getChildAt(index), label)?.let { return it }
        return null
    }
}
