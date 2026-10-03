package com.shilapi.xcertplay

import com.shilapi.xcertplay.media.AudioOutputRole
import android.app.Activity
import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class L7AudioRouteDialogTest {
    private fun find(view: View, label: String): View? {
        if (view is L7SettingRow) {
            if (findText(view, label) != null) return view
        }
        return findText(view, label)
    }

    private fun findText(view: View, label: String): View? {
        if (view is TextView && view.text.toString() == label) return view
        if (view is ViewGroup) for (index in 0 until view.childCount)
            find(view.getChildAt(index), label)?.let { return it }
        return null
    }

    @Test fun selectingDoesNotSaveUntilOuterConfirmationAndCancelDiscards() {
        val context = Robolectric.buildActivity(Activity::class.java).setup().get().apply {
            setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        }
        val commits = mutableListOf<Int>()
        val dialog = L7AudioRouteDialog.show(context, "媒体音频流", 0, AudioOutputRole.MEDIA) { commits.add(it) }
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        find(dialog.window!!.decorView, "高级输出策略")!!.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val selector = ShadowAlertDialog.getLatestAlertDialog()
        selector.listView.performItemClick(selector.listView.adapter.getView(3, null, selector.listView), 3, 3)
        selector.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(commits.isEmpty())
        assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        dialog.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(commits.isEmpty())
        val reopened = L7AudioRouteDialog.show(context, "媒体音频流", 0, AudioOutputRole.MEDIA) { commits.add(it) }
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(find(reopened.window!!.decorView, "0 · 内置推荐（按当前用途）"))
        reopened.dismiss()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun savingOuterDialogCommitsChosenStreamOnce() {
        val context = Robolectric.buildActivity(Activity::class.java).setup().get().apply {
            setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        }
        val commits = mutableListOf<Int>()
        val dialog = L7AudioRouteDialog.show(context, "导航音频流", 0, AudioOutputRole.ASSISTANT) { commits.add(it) }
        shadowOf(Looper.getMainLooper()).idle()
        find(dialog.window!!.decorView, "高级输出策略")!!.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val selector = ShadowAlertDialog.getLatestAlertDialog()
        selector.listView.performItemClick(selector.listView.adapter.getView(3, null, selector.listView), 3, 3)
        selector.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        save.performClick()
        save.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(103), commits)
        assertFalse(dialog.isShowing)
    }

}
