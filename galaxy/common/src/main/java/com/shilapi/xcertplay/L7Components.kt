package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import com.shilapi.xcertplay.host.R

/** 按 Flyme Auto 参考规范实现的 L7 原生组件；不依赖车机私有 widget。 */
internal object L7Components {
    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()

    fun backRow(context: Context, title: String, back: () -> Unit) = L7Header(context, title, onBack = back)

    fun iconButton(context: Context, icon: Int, label: String, click: () -> Unit) = ImageButton(context).apply {
        setImageResource(icon)
        contentDescription = label
        setPadding(dp(context, 16), dp(context, 16), dp(context, 16), dp(context, 16))
        minimumWidth = dp(context, 64)
        minimumHeight = dp(context, 64)
        L7Ui.bind(this) {
            imageTintList = ColorStateList.valueOf(context.getColor(R.color.product_ui_text))
            background = RippleDrawable(ColorStateList.valueOf(context.getColor(R.color.product_ui_ripple)),
                null, L7Ui.rounded(context, android.graphics.Color.WHITE, 8))
        }
        setOnClickListener { if (isEnabled) click() }
    }

    fun actionButton(context: Context, title: String, primary: Boolean = false, click: () -> Unit) = L7ActionButton(context).apply {
        text = title
        L7Ui.button(this, primary)
        setOnClickListener { if (isEnabled) click() }
    }

    fun text(context: Context, value: String, secondary: Boolean = false) = TextView(context).apply {
        text = value
        textSize = if (secondary) 16f else 20f
        typeface = Typeface.create(if (secondary) "sans-serif" else "sans-serif-medium", Typeface.NORMAL)
        includeFontPadding = false
        setLineSpacing(dp(context, 3).toFloat(), 1f)
        L7Ui.text(this, if (secondary) R.color.product_ui_muted else R.color.product_ui_text)
    }

    fun note(context: Context, value: String, warning: Boolean = false) = text(context, value, secondary = true).apply {
        setPadding(dp(context, 4), dp(context, 12), dp(context, 4), dp(context, 12))
        if (warning) L7Ui.text(this, R.color.product_ui_danger)
    }

    fun sectionTitle(context: Context, value: String) =
        L7Typography.text(context, value, L7Typography.Role.SECTION_TITLE)

    fun actionRow(context: Context, title: String, detail: String = "", icon: Int? = null, click: () -> Unit): L7SettingRow =
        L7SettingRow(context, title, detail, icon).apply {
            setAccessory(ImageView(context).apply {
                setImageResource(R.drawable.ic_l7_next)
                L7Ui.bind(this) { imageTintList = ColorStateList.valueOf(context.getColor(R.color.product_ui_muted)) }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, dp(context, 32), dp(context, 32))
            isFocusable = true
            L7Ui.rowFeedback(this)
            setOnClickListener { if (isEnabled) click() }
        }

    fun valueRow(context: Context, title: String, value: String, click: () -> Unit): L7SettingRow =
        actionRow(context, title, click = click).apply { setValue(value) }

    fun choice(parent: LinearLayout, title: String, options: List<String>, current: Int,
               reconnects: Boolean = true, save: (Int) -> Unit) {
        val context = parent.context
        var selection = current.coerceIn(options.indices)
        lateinit var row: L7SettingRow
        row = valueRow(context, title, options[selection]) {
            select(context, title, options, selection,
                context.getString(if (reconnects && CarPlayBackgroundSession.hasSession()) R.string.l7_save_next_connection else R.string.save)) { chosen ->
                save(chosen)
                selection = chosen
                row.setValue(options[chosen])
            }
        }
        parent.addView(row)
    }

    /** 分类入口仅用右侧箭头提示下一层，名称与说明保持原有布局。 */
    fun categoryRow(context: Context, title: String, detail: String, icon: Int, click: () -> Unit): L7SettingRow =
        actionRow(context, title, detail, icon, click)

    fun switchRow(context: Context, title: String, detail: String, checked: Boolean, save: (Boolean) -> Unit): L7SettingRow =
        L7SettingRow(context, title, detail).apply {
            val control = Switch(context).apply {
                contentDescription = title
                isChecked = checked
                minHeight = dp(context, 64)
                L7Ui.bind(this) {
                    val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
                    thumbTintList = ColorStateList(states, intArrayOf(context.getColor(R.color.product_ui_accent), context.getColor(R.color.product_ui_muted)))
                    trackTintList = ColorStateList(states, intArrayOf(context.getColor(R.color.product_ui_selected), context.getColor(R.color.product_ui_border)))
                }
                // 行点击调用控件入口，拖动与点击都只经这个监听器提交一次。
                setOnCheckedChangeListener { _, value -> if (!updatingSwitch) save(value) }
            }
            setAccessory(control)
            isFocusable = true
            setOnClickListener { if (isEnabled && control.isEnabled) control.performClick() }
            L7Ui.rowFeedback(this)
        }

    fun styleDialog(dialog: AlertDialog) {
        listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL).forEach { which ->
            dialog.getButton(which)?.apply {
                minHeight = dp(context, 64)
                L7Ui.button(this, primary = which == AlertDialog.BUTTON_POSITIVE, radius = 8)
            }
        }
    }

    /** 待选值只存在于本次弹窗；取消不保存，确认只提交发生变化的值。 */
    fun select(context: Context, title: String, options: List<String>, current: Int, applyLabel: String,
               onPreview: (Int) -> Unit = {}, onDismiss: () -> Unit = {}, onApply: (Int) -> Unit): AlertDialog {
        var pending = current
        var committed = false
        val dialog = L7Dialogs.builder(context).setTitle(title)
            .setSingleChoiceItems(options.toTypedArray(), current) { dialog, index ->
                pending = index
                onPreview(index)
                (dialog as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = pending != current
            }
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(applyLabel, null).create()
        dialog.setOnDismissListener { onDismiss() }
        dialog.setOnShowListener {
            val apply = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            apply.isEnabled = false
            apply.setOnClickListener {
                if (pending == current || committed) return@setOnClickListener
                committed = true
                // 先关闭，防止快速重复点击对一个任务提交两次。
                dialog.dismiss()
                onApply(pending)
            }
            styleDialog(dialog)
        }
        dialog.show()
        return dialog
    }
}
