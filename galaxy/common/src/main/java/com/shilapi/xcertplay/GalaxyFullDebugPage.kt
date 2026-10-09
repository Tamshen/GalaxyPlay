package com.shilapi.xcertplay

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import com.shilapi.xcertplay.host.R
import java.io.Closeable

/** 主页面只显示当前任务与停止；需要人工观察时用同一模态框确认、记录异常或重试。 */
internal class GalaxyFullDebugPage(private val activity: Activity, parent: LinearLayout,
    private val autoStart: Boolean, codecController: GalaxyCodecProbeController,
    private val grant: () -> Unit, onLogs: () -> Unit) : Closeable, SurfaceHolder.Callback {
    private val handler = Handler(Looper.getMainLooper())
    private var closed = false
    private var foreground = true
    private var pendingStart = autoStart
    private var permissionPending = false
    private var dialog: AlertDialog? = null
    private var promptGeneration = -1L
    private var promptToken = ""
    private var promptBody: android.widget.TextView? = null
    private val status: L7SettingRow
    private val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal)
    private val start: android.widget.Button
    private val stop: android.widget.Button
    private val logs: android.widget.Button
    private val preview = object : SurfaceView(activity) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            setMeasuredDimension(width, width * 9 / 16)
        }
    }
    private val flow = GalaxyDebugFlow(GalaxyDebugChecks.build(activity, codecController) { preview.holder.surface },
        GalaxyDebugChecks::now, L7DebugLog::record)
    private var statusRow: L7SettingRow? = null
    private val tick = object : Runnable {
        override fun run() { if (!closed && foreground) { update(); handler.postDelayed(this, 200) } }
    }
    init {
        status = L7SettingsSection.add(parent, description = text(R.string.full_debug_intro)) { card ->
            card.addView(L7SettingRow(activity, text(R.string.full_debug_title)).also { statusRow = it })
        }.let { statusRow!! }
        progress.max = flow.checks.size
        parent.addView(progress, LinearLayout.LayoutParams(-1, L7Components.dp(activity, 8)).apply {
            bottomMargin = L7Components.dp(activity, 20)
        })
        preview.contentDescription = text(R.string.codec_probe_preview)
        preview.holder.setFixedSize(640, 360); preview.holder.addCallback(this)
        parent.addView(preview, LinearLayout.LayoutParams(-1, -2))
        var startButton: android.widget.Button? = null
        var stopButton: android.widget.Button? = null
        var logsButton: android.widget.Button? = null
        L7SettingsSection.actions(parent) { actions ->
            startButton = L7Components.actionButton(activity, text(R.string.full_debug_start), true) { begin() }.also(actions::addView)
            stopButton = L7Components.actionButton(activity, text(R.string.full_debug_stop)) { cancel() }.also(actions::addView)
            logsButton = L7Components.actionButton(activity, text(R.string.full_debug_logs)) { onLogs() }.also(actions::addView)
        }
        start = startButton!!; stop = stopButton!!; logs = logsButton!!
        handler.post(tick)
    }
    private fun text(id: Int) = activity.getString(id)
    private fun begin() {
        pendingStart = false
        if (!closed && foreground && L7Agreement.canUse(activity)) flow.start()
        update()
    }
    private fun update() {
        if (closed) return
        if (pendingStart && L7Agreement.canUse(activity)) { pendingStart = false; flow.start() }
        flow.poll()
        val check = flow.checks.getOrNull(flow.index)
        val active = flow.phase in setOf(GalaxyDebugFlow.Phase.RUNNING, GalaxyDebugFlow.Phase.QUESTION, GalaxyDebugFlow.Phase.RELEASING)
        progress.progress = flow.index
        status.setValue(when {
            flow.phase == GalaxyDebugFlow.Phase.FINISHED -> text(R.string.full_debug_finished)
            flow.phase == GalaxyDebugFlow.Phase.STOPPED -> text(R.string.full_debug_stopped)
            active && check != null -> activity.getString(R.string.full_debug_progress, flow.index + 1, flow.checks.size, text(check.title))
            else -> text(R.string.full_debug_intro)
        })
        status.setDescription(if (active && check != null) text(check.body) else "")
        status.setFeedback(when {
            !active && !flow.canStart -> text(R.string.full_debug_release_unconfirmed)
            !active && flow.phase != GalaxyDebugFlow.Phase.IDLE -> activity.getString(R.string.full_debug_summary, flow.completed, flow.missing, flow.unsupported)
            else -> flow.evidence.detail
        })
        preview.visibility = if (check?.id?.startsWith("CODEC_") == true && check.id != "CODEC_UNAVAILABLE" && active) View.VISIBLE else View.GONE
        start.visibility = if (active) View.GONE else View.VISIBLE
        start.isEnabled = foreground && flow.canStart
        stop.visibility = if (active) View.VISIBLE else View.GONE
        logs.visibility = if (active || flow.phase == GalaxyDebugFlow.Phase.IDLE) View.GONE else View.VISIBLE
        if (flow.phase == GalaxyDebugFlow.Phase.QUESTION && check != null && foreground && !permissionPending) question(check)
        else { dialog?.dismiss(); dialog = null; promptGeneration = -1 }
    }
    private fun question(check: GalaxyDebugFlow.Check) {
        if (dialog == null || promptGeneration != flow.generation) {
            dialog?.dismiss(); promptGeneration = flow.generation
            val generation = flow.generation
            promptBody = L7Typography.text(activity, text(check.body), L7Typography.Role.DESCRIPTION)
            dialog = L7Dialogs.builder(activity).setTitle(text(check.title))
                .setView(promptBody!!).setPositiveButton(R.string.full_debug_confirm, null)
                .setNegativeButton(R.string.full_debug_missing, null).setNeutralButton(R.string.full_debug_retry, null)
                .setOnCancelListener { cancel() }.show().also { window ->
                    window.setOnKeyListener { _, _, event -> VehicleSteeringInputLog.key(event, "vehicle-settings-key"); false }
                    listOf(AlertDialog.BUTTON_POSITIVE to GalaxyDebugFlow.Answer.CONFIRM,
                        AlertDialog.BUTTON_NEGATIVE to GalaxyDebugFlow.Answer.MISSING,
                        AlertDialog.BUTTON_NEUTRAL to GalaxyDebugFlow.Answer.RETRY).forEach { (button, answer) ->
                        window.getButton(button).setOnClickListener {
                            if (closed || !foreground || generation != flow.generation) return@setOnClickListener
                            if (answer == GalaxyDebugFlow.Answer.RETRY && check.id == "MICROPHONE" &&
                                activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                permissionPending = true; dialog?.dismiss(); dialog = null; grant()
                            } else if (flow.answer(generation, promptToken, answer)) {
                                window.dismiss(); dialog = null; update()
                            } else update()
                        }
                    }
                }
        }
        promptToken = flow.evidence.token
        val message = listOf(text(check.body), flow.evidence.detail,
            if (flow.evidence.unavailable && check.id != "CODEC_UNAVAILABLE") text(R.string.full_debug_unavailable) else "").filter { it.isNotBlank() }.joinToString("\n\n")
        if (promptBody?.text?.toString() != message) promptBody?.text = message
        dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = flow.evidence.canConfirm
    }
    fun permissionResult(granted: Boolean) {
        permissionPending = false
        if (!closed && granted) flow.answer(flow.generation, flow.evidence.token, GalaxyDebugFlow.Answer.RETRY)
    }
    private fun cancel() { dialog?.dismiss(); dialog = null; flow.stop(); L7ReportingTests.stop("SUITE_USER"); update() }
    fun background() {
        foreground = false; handler.removeCallbacks(tick); dialog?.dismiss(); dialog = null
        val observingReport = flow.phase == GalaxyDebugFlow.Phase.QUESTION &&
            flow.checks.getOrNull(flow.index)?.id?.startsWith("REPORT_") == true
        if (!permissionPending && !observingReport) { flow.stop("BACKGROUND"); L7ReportingTests.stop("SUITE_BACKGROUND") }
    }
    fun resume() { if (!closed) { foreground = true; handler.removeCallbacks(tick); handler.post(tick) } }
    override fun surfaceCreated(holder: SurfaceHolder) { update() }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { update() }
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        val id = flow.checks.getOrNull(flow.index)?.id
        if (!closed && id?.startsWith("CODEC_") == true && id != "CODEC_UNAVAILABLE") flow.stop("SURFACE_LOST")
    }
    override fun close() {
        closed = true; pendingStart = false; handler.removeCallbacks(tick); dialog?.dismiss(); dialog = null
        flow.close(); L7ReportingTests.stop("SUITE_CLOSED"); preview.holder.removeCallback(this)
    }
}
