package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.shilapi.xcertplay.host.R

internal data class L7TaskProgress(
    val running: Boolean, val message: String, val detail: String = "",
    val completed: Int = 0, val total: Int = 0, val waiting: Boolean = false,
    val result: Boolean = false, val retry: Boolean = false, val error: Boolean = false,
)

/** 同一窗口展示执行、停止和结果；返回先确认，关闭后撤销轮询与窗口引用。 */
internal class L7TaskProgressDialog(
    private val activity: Activity,
    title: Int,
    private val stopLabel: Int,
    private val closeHint: Int,
    private val snapshot: () -> L7TaskProgress,
    private val onStop: () -> Unit,
    private val onRetry: () -> Unit = {},
    private val onResult: () -> Unit = {},
    private val onDismiss: () -> Unit = {},
) {
    private val main = Handler(Looper.getMainLooper())
    private var confirmation: AlertDialog? = null
    private var paused = false
    private val message = L7Components.text(activity, "").apply {
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val detail = L7Components.text(activity, "", secondary = true)
    private val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        L7Ui.bind(this) {
            val tint = ColorStateList.valueOf(activity.getColor(R.color.product_ui_accent))
            progressTintList = tint; indeterminateTintList = tint
        }
    }
    private val body = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        addView(message)
        addView(progress, LinearLayout.LayoutParams(-1, L7Components.dp(activity, 8)).apply {
            topMargin = L7Components.dp(activity, 20); bottomMargin = L7Components.dp(activity, 20)
        })
        addView(detail)
    }
    internal val dialog: AlertDialog = L7Dialogs.builder(activity).setTitle(title).setView(body)
        .setPositiveButton(stopLabel, null).setNegativeButton(R.string.close, null)
        .setOnCloseRequest { requestClose() }.setOnDismissListener {
            main.removeCallbacksAndMessages(null)
            confirmation?.dismiss(); confirmation = null
            onDismiss()
        }.create()
    private val tick = Runnable { update() }

    init {
        dialog.setOnShowListener {
            dialog.setCanceledOnTouchOutside(false)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { requestClose() }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val state = snapshot()
                when {
                    state.running -> onStop()
                    state.retry -> onRetry()
                    state.result -> { dialog.dismiss(); onResult(); return@setOnClickListener }
                }
                update()
            }
            update()
        }
    }

    fun show() = dialog.show()
    fun dismiss() = dialog.dismiss()
    fun pause() { paused = true; main.removeCallbacks(tick) }
    fun resume() { paused = false; update() }
    fun stop() { if (snapshot().running) onStop(); update() }

    private fun update() {
        main.removeCallbacks(tick)
        if (!dialog.isShowing || activity.isDestroyed) return
        val state = snapshot()
        if (!state.running) { confirmation?.dismiss(); confirmation = null }
        text(message, state.message)
        text(detail, state.detail)
        detail.visibility = if (state.detail.isEmpty()) View.GONE else View.VISIBLE
        L7Ui.text(message, if (state.error) R.color.product_ui_warning else R.color.product_ui_text)
        progress.isIndeterminate = state.waiting || state.running && state.total <= 0
        progress.progress = if (state.total > 0) (state.completed * 100L / state.total).toInt().coerceIn(0, 100) else 0
        progress.contentDescription = state.message
        val action = when {
            state.running -> stopLabel
            state.retry -> R.string.l7_log_retry
            state.result -> R.string.l7_probe_results
            else -> null
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
            visibility = if (action == null) View.GONE else View.VISIBLE
            if (action != null && text.toString() != activity.getString(action)) {
                setText(action); L7Ui.refresh(this)
            }
        }
        if (!paused && (state.running || state.waiting)) main.postDelayed(tick, 200)
    }

    private fun requestClose() {
        if (!snapshot().running) { dialog.dismiss(); return }
        if (confirmation?.isShowing == true) return
        confirmation = L7Dialogs.builder(activity).setTitle(R.string.l7_task_end_title).setMessage(closeHint)
            .setNegativeButton(R.string.l7_task_continue, null)
            .setPositiveButton(R.string.l7_task_end_close) { _, _ ->
                if (snapshot().running) onStop()
                dialog.dismiss()
            }.setOnDismissListener { confirmation = null }.show()
    }

    private fun text(view: TextView, value: String) { if (view.text.toString() != value) view.text = value }
}
