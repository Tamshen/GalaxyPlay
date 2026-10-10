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
    internal var updatingSwitch = false
        private set
    private val iconInset = if (icon == null) 0 else dp(48)
    private var leadingAccessoryWidth = 0
    val textInset get() = dp(20) + iconInset + leadingAccessoryWidth

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

    fun setAccessory(view: View, width: Int = -2, height: Int = -2, slotWidth: Int = dp(64), leading: Boolean = false) {
        accessory.removeAllViews()
        leadingAccessoryWidth = if (leading) slotWidth else 0
        removeView(accessory)
        addView(accessory, if (leading) 0 else childCount, LayoutParams(slotWidth, -2))
        // 开关置于文字前，箭头仍在末端；视觉尺寸和整行命中区域分别处理。
        accessory.addView(view, FrameLayout.LayoutParams(width, height,
            (if (leading) Gravity.START else Gravity.END) or Gravity.CENTER_VERTICAL))
        accessory.visibility = VISIBLE
        view.isEnabled = isEnabled
    }

    /** 外部服务状态回填不等于用户操作，不能再次触发权限或服务调用。 */
    fun setSwitchChecked(checked: Boolean) {
        val control = accessory.getChildAt(0) as? android.widget.CompoundButton ?: return
        updatingSwitch = true
        try { control.isChecked = checked } finally { updatingSwitch = false }
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
    private var textHeight = 0
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
        val textWidth = if (inline) {
            measureText(value, width, false)
            width - value.measuredWidth - valueGap
        } else {
            width
        }
        measureText(label, textWidth)
        firstHeight = label.measuredHeight
        textHeight = firstHeight
        listOf(value, description, feedback).forEach { view ->
            if (view.visibility != GONE && !(view === value && inline)) {
                measureText(view, textWidth)
                textHeight += gap + view.measuredHeight
            }
        }
        val height = maxOf(textHeight, if (inline) value.measuredHeight else 0)
        setMeasuredDimension(width, resolveSize(height, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        fun place(view: View, x: Int, y: Int) {
            val start = if (rtl) width - x - view.measuredWidth else x
            view.layout(start, y, start + view.measuredWidth, y + view.measuredHeight)
        }
        // 短值独立于标题与说明，按整条文字区居中，与右侧操作槽保持同一中心线。
        val textTop = (height - textHeight) / 2
        place(label, 0, textTop)
        if (inline) place(value, width - value.measuredWidth, (height - value.measuredHeight) / 2)
        var y = textTop + firstHeight
        listOf(value, description, feedback).forEach { view ->
            if (view.visibility != GONE && !(view === value && inline)) {
                y += gap
                place(view, 0, y)
                y += view.measuredHeight
            }
        }
    }
}
