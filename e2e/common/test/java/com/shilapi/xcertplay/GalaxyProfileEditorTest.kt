package com.shilapi.xcertplay

import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class GalaxyProfileEditorTest {
    private fun activity(): ComponentActivity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get().apply {
        setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        File(filesDir, "configurations").deleteRecursively()
    }
    @Test fun cancelConfirmsDirtyDraftAndPreservesOriginalFile() {
        val activity = activity()
        val repository = GalaxyProfiles(activity)
        val original = repository.active()
        GalaxyProfileEditor.show(activity, original) {}
        val editor = dialog()
        name(editor).setText("Unsaved name")
        editor.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        val confirmation = dialog()
        assertNotSame(editor, confirmation)
        assertEquals(original.name, repository.active().name)
        confirmation.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertTrue(editor.isShowing)
        editor.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(editor.isShowing)
        assertEquals(original, repository.active())
        assertNull(ViewModelProvider(activity)[GalaxyProfileEditorState::class.java].draft)
    }
    @Test fun saveWritesOnceAndEmptyNameKeepsEditorOpenWithDraft() {
        val activity = activity()
        val repository = GalaxyProfiles(activity)
        val original = repository.active()
        var refreshed = 0
        GalaxyProfileEditor.show(activity, original) { refreshed++ }
        val editor = dialog()
        name(editor).setText("")
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(editor.isShowing)
        assertNotNull(name(editor).error)
        assertEquals(original, repository.active())
        name(editor).setText("Saved test")
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertFalse(editor.isShowing)
        assertEquals(1, refreshed)
        assertEquals(original.revision + 1, repository.active().revision)
        assertEquals("Saved test", repository.active().name)
    }
    @Test fun categoryChangesKeepDraftAndDoNotChangeGlobalVideoOrAudioSettings() {
        val activity = activity()
        val repository = GalaxyProfiles(activity)
        val original = repository.active()
        GalaxyProfileEditor.show(activity, original) {}
        val editor = dialog()
        name(editor).setText("Across categories")
        val category = descendants(editor.window!!.decorView).filterIsInstance<L7SettingRow>()
            .first { it.titleView.text.toString() == activity.getString(R.string.profile_category) }
        category.performClick()
        val picker = dialog()
        picker.listView.performItemClick(picker.listView.adapter.getView(1, null, picker.listView), 1, 1)
        picker.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals("Across categories", name(editor).text.toString())
        assertEquals(original, repository.active())
        assertTrue(descendants(editor.window!!.decorView).filterIsInstance<L7SettingRow>()
            .any { it.titleView.text.toString() == activity.getString(R.string.frame_rate) })
    }
    private fun name(dialog: AlertDialog) = descendants(dialog.window!!.decorView).filterIsInstance<EditText>()
        .first { it.hint?.toString() == dialog.context.getString(R.string.profile_name) }
    private fun dialog(): AlertDialog {
        shadowOf(Looper.getMainLooper()).idle()
        return ShadowAlertDialog.getLatestAlertDialog()
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
