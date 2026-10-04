package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class L7ModalDialogTest {
    @Test fun messageDialogRepaintsOnOwnerNightModeChange() {
        val context = activity()
        val dialog = L7ModalDialog(context, L7Dialogs.Content().apply { message = "蓝牙媒体检查" })
        dialog.show()
        fun findMessage(view: View): android.widget.TextView? {
            if (view is android.widget.TextView && view.text.toString() == "蓝牙媒体检查") return view
            if (view is ViewGroup) for (index in 0 until view.childCount) findMessage(view.getChildAt(index))?.let { return it }
            return null
        }
        val message = findMessage(dialog.window!!.decorView)!!
        org.robolectric.RuntimeEnvironment.setQualifiers("+night")
        // common 不包含 mobile 的昼夜配色覆盖；用过期颜色验证所属界面的配置更新确实重绘。
        message.setTextColor(android.graphics.Color.MAGENTA)
        L7Dialogs.refresh(context)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertNotEquals(android.graphics.Color.MAGENTA, message.currentTextColor)
        assertEquals(context.getColor(com.shilapi.xcertplay.host.R.color.product_ui_muted), message.currentTextColor)
        dialog.dismiss()
    }
    private fun activity() = Robolectric.buildActivity(Activity::class.java).setup().get().apply {
        setTheme(android.R.style.Theme_Material_Light_NoActionBar)
    }

    @Test fun keyboardClosingDialogRestoresEntryAfterPageContentRefresh() {
        val context = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        val original = L7Components.actionRow(context, "认证来源") {}
        context.setContentView(original)
        // 显式模拟键盘定位入口；触屏点击本身不应强制获得键盘焦点。
        original.requestFocusFromTouch()
        original.performClick()
        assertTrue("打开弹窗前入口应获得焦点", original.hasFocus())
        val dialog = L7ModalDialog(context, L7Dialogs.Content().apply { title = "选择来源" })
        dialog.show()
        val replacement = L7Components.actionRow(context, "认证来源") {}
        context.setContentView(replacement)
        dialog.dismiss()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue("新入口必须可见", replacement.isShown)
        assertTrue("新入口必须已挂载", replacement.isAttachedToWindow)
        assertTrue("焦点仍位于 ${context.currentFocus}", replacement.hasFocus())
    }

    @Test fun bodyPrimaryButtonKeepsItsPaletteAndEnabledState() {
        val context = activity()
        val button = L7Components.actionButton(context, "试听此声道", primary = true) {}
        val color = context.getColor(com.shilapi.xcertplay.host.R.color.product_ui_primary_text)
        val dialog = L7ModalDialog(context, L7Dialogs.Content().apply { view = button })
        dialog.show()
        assertEquals(color, button.currentTextColor)
        button.isEnabled = false
        assertEquals(context.getColor(com.shilapi.xcertplay.host.R.color.product_ui_disabled), button.currentTextColor)
        button.isEnabled = true
        L7Ui.refresh(dialog.window!!.decorView)
        assertEquals(color, button.currentTextColor)
        dialog.dismiss()
    }

    @Test fun stylingKeepsMultilineInputHeight() {
        val context = activity()
        val input = EditText(context).apply { minLines = 4; maxLines = 8 }
        val dialog = L7ModalDialog(context, L7Dialogs.Content().apply { view = input })
        dialog.show()
        assertEquals(4, input.minLines)
        assertEquals(8, input.maxLines)
        dialog.dismiss()
    }

    @Test fun callerDismissListenerSurvivesCreationAndReleasesPreview() {
        var released = 0
        val content = L7Dialogs.Content().apply {
            title = "声道预听"
            actions[AlertDialog.BUTTON_NEGATIVE] = L7Dialogs.Action("取消", null)
        }
        val dialog = L7ModalDialog(activity(), content)
        dialog.setOnDismissListener { released++ }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)!!.performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(1, released)
    }

    @Test fun changingSelectionOnlyPreviewsAndConfirmationRunsOnce() {
        var preview = -1
        var commits = 0
        val content = L7Dialogs.Content().apply {
            items = arrayOf("30", "60"); singleChoice = true; checked = 0
            itemClick = android.content.DialogInterface.OnClickListener { _, index -> preview = index }
            actions[AlertDialog.BUTTON_POSITIVE] = L7Dialogs.Action("保存", android.content.DialogInterface.OnClickListener { _, _ -> commits++ })
        }
        val dialog = L7ModalDialog(activity(), content).apply { show() }
        val list = dialog.listView!!
        list.performItemClick(list.adapter.getView(1, null, list), 1, 1)
        assertEquals(1, preview)
        assertEquals(0, commits)
        assertTrue(dialog.isShowing)
        val apply = dialog.getButton(AlertDialog.BUTTON_POSITIVE)!!
        apply.performClick(); apply.performClick()
        assertEquals(1, commits)
    }

    @Test fun busyCloseCannotCancelAndEnabledCloseDoesNotConfirm() {
        var cancelled = 0
        var commits = 0
        val content = L7Dialogs.Content().apply {
            actions[AlertDialog.BUTTON_POSITIVE] = L7Dialogs.Action("启用", android.content.DialogInterface.OnClickListener { _, _ -> commits++ })
            cancel = android.content.DialogInterface.OnCancelListener { cancelled++ }
        }
        val dialog = L7ModalDialog(activity(), content).apply { show() }
        fun findClose(view: View): View? {
            if (view.contentDescription?.toString() == dialog.context.getString(com.shilapi.xcertplay.host.R.string.close)) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) findClose(view.getChildAt(index))?.let { return it }
            return null
        }
        val close = findClose(dialog.window!!.decorView)!!
        dialog.setCancelable(false); close.performClick()
        assertTrue(dialog.isShowing)
        dialog.setCancelable(true); close.performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(1, cancelled)
        assertEquals(0, commits)
    }
}
