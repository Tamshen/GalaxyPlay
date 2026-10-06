package com.shilapi.xcertplay

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R

/** 视频失败单独覆盖画面，正常音频和连接仍由原会话持有；只在实际呈现后收起。 */
internal class L7VideoRecoveryPanel(context: Context, private val retry: () -> Boolean,
    settings: () -> Unit) : LinearLayout(context) {
    private val state = L7Components.text(context, context.getString(R.string.l7_video_failed_title))
    init {
        orientation = VERTICAL; gravity = Gravity.CENTER; isClickable = true
        val padding = L7Components.dp(context, 32)
        setPadding(padding, padding, padding, padding)
        L7Ui.bind(this) { setBackgroundColor(context.getColor(R.color.product_ui_background)) }
        state.gravity = Gravity.CENTER
        addView(state, row())
        addView(L7Components.text(context, context.getString(R.string.l7_video_failed_message), secondary = true)
            .apply { gravity = Gravity.CENTER }, row())
        addView(L7Components.actionButton(context, context.getString(R.string.l7_video_retry), primary = true) {
            if (visibility == View.VISIBLE) state.text = context.getString(
                if (retry()) R.string.l7_video_retrying else R.string.l7_video_retry_unavailable)
        }, row())
        addView(L7Components.actionButton(context, context.getString(R.string.l7_video_settings), click = settings), row())
        visibility = View.GONE
    }
    fun failed() { state.setText(R.string.l7_video_failed_title); visibility = View.VISIBLE }
    fun recovered() { visibility = View.GONE }
    private fun row() = LayoutParams(L7Components.dp(context, 560), -2).apply {
        width = (context.resources.displayMetrics.widthPixels - 2 * L7Components.dp(context, 32)).coerceAtLeast(1).coerceAtMost(width)
        topMargin = L7Components.dp(context, 20)
    }
}
