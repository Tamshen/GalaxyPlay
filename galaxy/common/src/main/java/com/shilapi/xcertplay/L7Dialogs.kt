package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.view.View
import java.lang.ref.WeakReference
import com.shilapi.xcertplay.host.R

/** 所有应用内弹窗共用一个入口；系统权限窗口仍由 Android 管理。 */
internal object L7Dialogs {
    private val showing = mutableListOf<WeakReference<L7ModalDialog>>()
    fun builder(context: Context) = Builder(context)

    fun track(dialog: L7ModalDialog) {
        showing.removeAll { it.get() == null || it.get() === dialog }
        showing.add(WeakReference(dialog))
    }

    fun untrack(dialog: L7ModalDialog) { showing.removeAll { it.get() == null || it.get() === dialog } }

    /** Activity 的窗口遍历不包含 Dialog，配置更新后单独刷新属于该界面的弹窗。 */
    fun refresh(context: Context) {
        showing.removeAll { it.get() == null }
        showing.forEach { it.get()?.refreshPalette(context) }
    }

    internal data class Action(val label: CharSequence, val click: DialogInterface.OnClickListener?)
    internal class Content {
        var title: CharSequence = ""
        var message: CharSequence? = null
        var view: View? = null
        var items: Array<out CharSequence>? = null
        var checked = -1
        var singleChoice = false
        var itemClick: DialogInterface.OnClickListener? = null
        var cancelable = true
        var cancel: DialogInterface.OnCancelListener? = null
        var dismiss: DialogInterface.OnDismissListener? = null
        var closeRequest: (() -> Unit)? = null
        val actions = linkedMapOf<Int, Action>()
    }

    class Builder(private val context: Context) {
        private val content = Content()
        private val platform = AlertDialog.Builder(context)

        fun setTitle(value: Int) = setTitle(context.getString(value))
        fun setTitle(value: CharSequence) = apply { content.title = value; platform.setTitle(value) }
        fun setMessage(value: Int) = setMessage(context.getString(value))
        fun setMessage(value: CharSequence) = apply { content.message = value; platform.setMessage(value) }
        fun setView(view: View) = apply { content.view = view; platform.setView(view) }
        fun setCancelable(value: Boolean) = apply { content.cancelable = value; platform.setCancelable(value) }
        fun setOnCancelListener(listener: DialogInterface.OnCancelListener) = apply {
            content.cancel = listener; platform.setOnCancelListener(listener)
        }
        fun setOnDismissListener(listener: DialogInterface.OnDismissListener) = apply {
            content.dismiss = listener; platform.setOnDismissListener(listener)
        }
        fun setOnCloseRequest(listener: () -> Unit) = apply { content.closeRequest = listener }

        fun setItems(items: Array<out CharSequence>, click: DialogInterface.OnClickListener) = apply {
            content.items = items; content.itemClick = click; content.singleChoice = false
            platform.setItems(items, click)
        }

        fun setSingleChoiceItems(items: Array<out CharSequence>, checked: Int, click: DialogInterface.OnClickListener) = apply {
            content.items = items; content.checked = checked; content.itemClick = click; content.singleChoice = true
            platform.setSingleChoiceItems(items, checked, click)
        }

        private fun action(which: Int, label: CharSequence, click: DialogInterface.OnClickListener?) = apply {
            content.actions[which] = Action(label, click)
            when (which) {
                AlertDialog.BUTTON_POSITIVE -> platform.setPositiveButton(label, click)
                AlertDialog.BUTTON_NEGATIVE -> platform.setNegativeButton(label, click)
                else -> platform.setNeutralButton(label, click)
            }
        }
        fun setPositiveButton(label: Int, click: DialogInterface.OnClickListener?) = setPositiveButton(context.getString(label), click)
        fun setPositiveButton(label: CharSequence, click: DialogInterface.OnClickListener?) = action(AlertDialog.BUTTON_POSITIVE, label, click)
        fun setNegativeButton(label: Int, click: DialogInterface.OnClickListener?) = setNegativeButton(context.getString(label), click)
        fun setNegativeButton(label: CharSequence, click: DialogInterface.OnClickListener?) = action(AlertDialog.BUTTON_NEGATIVE, label, click)
        fun setNeutralButton(label: Int, click: DialogInterface.OnClickListener?) = setNeutralButton(context.getString(label), click)
        fun setNeutralButton(label: CharSequence, click: DialogInterface.OnClickListener?) = action(AlertDialog.BUTTON_NEUTRAL, label, click)

        fun create(): AlertDialog = if (content.closeRequest != null || context.resources.getBoolean(R.bool.config_l7_product_ui))
            L7ModalDialog(context, content) else platform.create()
        fun show(): AlertDialog = create().apply { show() }
    }
}
