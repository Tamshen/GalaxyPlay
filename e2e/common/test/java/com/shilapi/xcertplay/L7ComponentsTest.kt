package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.widget.Switch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class L7ComponentsTest {
    private fun activity() = Robolectric.buildActivity(Activity::class.java).setup().get().apply {
        setTheme(android.R.style.Theme_Material_Light_NoActionBar)
    }

    // 模拟用户看到弹窗后再操作，先处理 Android 异步的 onShow 消息。
    private fun settle() { shadowOf(Looper.getMainLooper()).idle() }

    @Test fun disabledChoicePreservesValueAndPreventsRepeatedAction() {
        var clicks = 0
        val row = L7Components.valueRow(activity(), "当前值", "30 帧/秒") { clicks++ }
        row.setFeedback("正在保存")
        row.isEnabled = false
        row.performClick()
        assertEquals(0, clicks)
        assertEquals(1f, row.alpha)
        assertEquals("30 帧/秒", row.valueView.text.toString())
        row.isEnabled = true
        row.performClick()
        assertEquals(1, clicks)
    }

    @Test fun rowAndSwitchClicksEachSubmitExactlyOnce() {
        val changes = mutableListOf<Boolean>()
        val row = L7Components.switchRow(activity(), "自动连接", "启动时连接", false) { changes.add(it) }
        val controls = arrayListOf<View>()
        row.findViewsWithText(controls, "自动连接", View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
        val control = controls.filterIsInstance<Switch>().single()
        row.performClick()
        assertTrue(control.isChecked)
        assertEquals(listOf(true), changes)
        control.performClick()
        assertFalse(control.isChecked)
        assertEquals(listOf(true, false), changes)
        control.isEnabled = false
        row.performClick()
        assertEquals(listOf(true, false), changes)
    }

    @Test fun cancelDiscardsPendingChoiceAndReopenUsesSavedValue() {
        val context = activity()
        val commits = mutableListOf<Int>()
        val first = L7Components.select(context, "帧率", listOf("30", "60"), 0, "保存") { commits.add(it) }
        settle()
        first.listView.performItemClick(first.listView.adapter.getView(1, null, first.listView), 1, 1)
        assertTrue(first.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        first.cancel()
        assertTrue(commits.isEmpty())
        val reopened = L7Components.select(context, "帧率", listOf("30", "60"), 0, "保存") { commits.add(it) }
        settle()
        assertEquals(0, reopened.listView.checkedItemPosition)
        assertFalse(reopened.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        reopened.dismiss()
    }

    @Test fun changedChoiceCommitsOnceEvenWithRepeatedConfirmation() {
        val commits = mutableListOf<Int>()
        val dialog = L7Components.select(activity(), "帧率", listOf("30", "60"), 0, "保存") { commits.add(it) }
        settle()
        val apply = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        apply.performClick()
        assertTrue(commits.isEmpty())
        dialog.listView.performItemClick(dialog.listView.adapter.getView(1, null, dialog.listView), 1, 1)
        apply.performClick()
        apply.performClick()
        assertEquals(listOf(1), commits)
        assertFalse(dialog.isShowing)
    }
}
