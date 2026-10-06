package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.widget.LinearLayout
import com.shilapi.xcertplay.media.NavigationOutputDevice
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30])
class GalaxyNavigationOutputTest {
    @Test fun missingSavedDeviceCanBeClearedOnlyAfterConfirmationAndModelsStayIsolated() {
        val context = Robolectric.buildActivity(Activity::class.java).setup().get()
        context.setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        L7AudioTemplates.selectModel(context, L7AudioTemplates.Model.L7)
        val saved = NavigationOutputDevice(7, 21, "bus1_navigation_out", "Navigation")
        GalaxyNavigationOutput.save(context, saved)
        val parent = LinearLayout(context)
        GalaxyNavigationOutput.add(context, parent)
        val row = parent.getChildAt(0)
        row.performClick(); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        var dialog = ShadowAlertDialog.getLatestAlertDialog()
        dialog.listView.performItemClick(dialog.listView.adapter.getView(0, null, dialog.listView), 0, 0)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertEquals(saved, GalaxyNavigationOutput.load(context))
        row.performClick(); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); dialog = ShadowAlertDialog.getLatestAlertDialog()
        dialog.listView.performItemClick(dialog.listView.adapter.getView(0, null, dialog.listView), 0, 0)
        assertTrue("选择自动后确认按钮应可用", dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertNull(GalaxyNavigationOutput.load(context))
        GalaxyNavigationOutput.save(context, saved)
        L7AudioTemplates.selectModel(context, L7AudioTemplates.Model.L6)
        assertNull(GalaxyNavigationOutput.load(context))
        L7AudioTemplates.selectModel(context, L7AudioTemplates.Model.L7)
        assertEquals(saved, GalaxyNavigationOutput.load(context))
    }
}
