package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30])
class L7HotspotEditorTest {
    private fun activity() = Robolectric.buildActivity(Activity::class.java).setup().get().apply {
        setTheme(android.R.style.Theme_Material_Light_NoActionBar)
    }
    private fun inputs(dialog: AlertDialog) = descendants(dialog.window!!.decorView).filterIsInstance<EditText>()
    @Test fun cancellingKeepsOldCredentialsAndClearsPrivateInputsWithoutSavingViewState() {
        var applied = false
        val picker = L7HotspotEditor.show(activity(), "test-hotspot", "test-secret") { _, _ -> applied = true }
        shadowOf(Looper.getMainLooper()).idle()
        val fields = inputs(picker)
        assertEquals(2, fields.size)
        assertTrue(fields.none { it.isSaveEnabled })
        fields[0].setText("changed-hotspot")
        picker.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(applied)
        assertTrue(fields.all { it.text.isEmpty() })
    }
    @Test fun invalidNameOrPasswordKeepsDialogOpenAndNeverAppliesTheCandidate() {
        var count = 0
        val picker = L7HotspotEditor.show(activity(), "", "short") { _, _ -> count++ }
        shadowOf(Looper.getMainLooper()).idle()
        val save = picker.getButton(AlertDialog.BUTTON_POSITIVE)
        save.performClick()
        assertTrue(picker.isShowing)
        inputs(picker)[0].setText("test-hotspot")
        save.performClick()
        assertTrue(picker.isShowing)
        assertEquals(0, count)
        picker.dismiss()
    }
    @Test fun validPairAppliesExactlyOnceAndEmptyPasswordRemainsSupported() {
        val changes = mutableListOf<Pair<String, String>>()
        val picker = L7HotspotEditor.show(activity(), " test-hotspot ", "test-secret") { name, password ->
            changes += name to password
        }
        shadowOf(Looper.getMainLooper()).idle()
        val save = picker.getButton(AlertDialog.BUTTON_POSITIVE)
        save.performClick(); save.performClick()
        assertEquals(listOf("test-hotspot" to "test-secret"), changes)
        assertFalse(picker.isShowing)
        val open = L7HotspotEditor.show(activity(), "open-hotspot", "") { name, password -> changes += name to password }
        shadowOf(Looper.getMainLooper()).idle()
        open.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals("open-hotspot" to "", changes.last())
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
