package com.shilapi.xcertplay

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup

/** 弹窗关闭后恢复入口焦点；认证保存等局部刷新后按稳定条目名称寻找新实例。 */
internal class L7DialogFocus(context: Context) {
    private val activity = context as? Activity
    private val opener = activity?.window?.decorView?.findFocus()
    private val rowTitle = (opener as? L7SettingRow)?.titleView?.text?.toString()

    fun restore() {
        val owner = activity ?: return
        owner.window.decorView.post {
            if (owner.isFinishing || owner.isDestroyed) return@post
            val target = opener?.takeIf { it.isAttachedToWindow && it.isShown && it.isEnabled }
                ?: rowTitle?.let { findRow(owner.window.decorView, it) }
            target?.takeIf { it.isShown && it.isEnabled }?.requestFocusFromTouch()
        }
    }

    private fun findRow(view: View, title: String): View? {
        if (view is L7SettingRow && view.titleView.text.toString() == title) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findRow(view.getChildAt(index), title)?.let { return it }
        }
        return null
    }
}
