package com.shilapi.xcertplay

import android.app.Activity
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R

/** 轻量结果提示显示在应用窗口内；权限说明和需要确认的动作继续使用模态框。 */
internal object L7Notice {
    fun show(activity: Activity, message: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        val host = activity.findViewById<FrameLayout>(android.R.id.content) ?: return
        host.findViewById<View>(R.id.l7_notice)?.let { host.removeView(it) }
        fun dp(value: Int) = L7Components.dp(activity, value)
        val notice = LinearLayout(activity).apply {
            id = R.id.l7_notice
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            minimumHeight = dp(64)
            elevation = dp(12).toFloat()
            L7Ui.surface(this)
            addView(ImageView(activity).apply {
                setImageResource(R.drawable.ic_dp_about)
                imageTintList = ColorStateList.valueOf(activity.getColor(R.color.product_ui_accent))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(L7Components.text(activity, message).apply {
                textSize = 18f
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(16) })
        }
        val width = (activity.resources.displayMetrics.widthPixels * .86f).toInt().coerceAtMost(dp(680))
        host.addView(notice, FrameLayout.LayoutParams(width, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(24) })
        notice.postDelayed({ if (notice.parent === host) host.removeView(notice) }, 4500)
        notice.setOnClickListener { host.removeView(notice) }
    }
}
