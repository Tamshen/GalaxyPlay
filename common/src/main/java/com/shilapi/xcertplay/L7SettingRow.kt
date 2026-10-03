package com.shilapi.xcertplay

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.host.R

/** 普通参数不占图标列；只读信息不占操作列。当前值、说明与反馈分别更新。 */
internal class L7SettingRow(context: Context, title: String, description: String = "", icon: Int? = null) : LinearLayout(context) {
    private val labels = SettingText(context)
    val titleView get() = labels.label
    val descriptionView get() = labels.description
    val valueView get() = labels.value
    val feedbackView get() = labels.feedback
    private val accessory = FrameLayout(context)
    val textInset = dp(20) + if (icon == null) 0 else dp(48)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(80)
        setPadding(dp(20), dp(16), dp(20), dp(16))
        if (icon != null) addView(ImageView(context).apply {
            setImageResource(icon)
            L7Ui.bind(this) { imageTintList = ColorStateList.valueOf(context.getColor(R.color.product_ui_text)) }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(16) })
        addView(labels, LayoutParams(0, -2, 1f))
        addView(accessory, LayoutParams(dp(64), -2))
        accessory.visibility = GONE
        titleView.text = title
        setDescription(description)
    }

    fun setDescription(value: String) = update(descriptionView, value)
    fun setValue(value: String) = update(valueView, value)
    fun setFeedback(value: String, error: Boolean = false) {
        update(feedbackView, value)
        L7Ui.text(feedbackView, if (error) R.color.product_ui_danger else R.color.product_ui_muted)
        feedbackView.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    fun setAccessory(view: View, width: Int = -2, height: Int = -2) {
        accessory.removeAllViews()
        // 操作槽保留文字间隔，图标与开关统一靠末端，避免窄箭头被居中后显得偏左。
        accessory.addView(view, FrameLayout.LayoutParams(width, height, Gravity.END or Gravity.CENTER_VERTICAL))
        accessory.visibility = VISIBLE
        view.isEnabled = isEnabled
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        // 禁用只减弱控件；名称、确认值和禁用原因保持可读。
        accessory.alpha = if (enabled) 1f else .4f
        for (index in 0 until accessory.childCount) accessory.getChildAt(index).isEnabled = enabled
    }

    private fun update(view: TextView, value: String) {
        if (view.text.toString() != value) view.text = value
        view.visibility = if (value.isEmpty()) GONE else VISIBLE
        minimumHeight = dp(if (descriptionView.visibility == VISIBLE || feedbackView.visibility == VISIBLE) 96 else 80)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (labels.measuredHeight > titleView.measuredHeight && measuredHeight < dp(96)) {
            setMeasuredDimension(measuredWidth, resolveSize(dp(96), heightMeasureSpec))
        }
    }

    private fun dp(value: Int) = L7Components.dp(context, value)
}

/** 用实际文字宽度决定值的排布，大字号和长值可自然增高，不截断关键信息。 */
private class SettingText(context: Context) : ViewGroup(context) {
    val label = L7Typography.text(context, "", L7Typography.Role.LABEL)
    val description = L7Typography.text(context, "", L7Typography.Role.DESCRIPTION)
    val value = L7Typography.text(context, "", L7Typography.Role.VALUE)
    val feedback = L7Typography.text(context, "", L7Typography.Role.FEEDBACK)
    private var inline = false
    private var firstHeight = 0
    private val gap = L7Components.dp(context, 4)
    private val valueGap = L7Components.dp(context, 16)

    init {
        listOf(label, value, description, feedback).forEach { addView(it, LayoutParams(-1, -2)) }
        listOf(value, description, feedback).forEach { it.visibility = GONE }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val naturalLabel = label.paint.measureText(label.text.toString())
        val naturalValue = value.paint.measureText(value.text.toString())
        inline = value.visibility == VISIBLE && !value.text.contains('\n') &&
            naturalValue <= width * .45f && naturalLabel + naturalValue + valueGap <= width
        fun measureText(view: View, available: Int, exact: Boolean = true) {
            view.measure(MeasureSpec.makeMeasureSpec(available.coerceAtLeast(0), if (exact) MeasureSpec.EXACTLY else MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        }
        if (inline) {
            measureText(value, width, false)
            measureText(label, width - value.measuredWidth - valueGap)
        } else {
            measureText(label, width)
            if (value.visibility == VISIBLE) measureText(value, width)
        }
        firstHeight = maxOf(label.measuredHeight, if (inline) value.measuredHeight else 0)
        var height = firstHeight
        listOf(value, description, feedback).forEach { view ->
            if (view.visibility != GONE && !(view === value && inline)) {
                measureText(view, width)
                height += gap + view.measuredHeight
            }
        }
        setMeasuredDimension(width, resolveSize(height, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        fun place(view: View, x: Int, y: Int) {
            val start = if (rtl) width - x - view.measuredWidth else x
            view.layout(start, y, start + view.measuredWidth, y + view.measuredHeight)
        }
        place(label, 0, (firstHeight - label.measuredHeight) / 2)
        if (inline) place(value, width - value.measuredWidth, (firstHeight - value.measuredHeight) / 2)
        var y = firstHeight
        listOf(value, description, feedback).forEach { view ->
            if (view.visibility != GONE && !(view === value && inline)) {
                y += gap
                place(view, 0, y)
                y += view.measuredHeight
            }
        }
    }
}
