package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R
import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong

/** 引导只持有交互进度；业务状态和样例证据仍由原测试组件管理。 */
internal class L7DebugGuide(
    private val activity: Activity, parent: LinearLayout, private val kind: String,
    private val steps: List<Step>, private val evidence: () -> LongArray = { longArrayOf() },
) : Closeable {
    data class Step(val title: Int, val body: Int, val target: () -> View? = { null },
        val ready: () -> Boolean = { true }, val enter: () -> Unit = {})
    private val state = (activity as? GalaxySettingsActivity)?.debugGuides?.state(kind) ?: L7DebugGuideStore.State()
    val memory get() = state.memory
    private var index: Int
        get() = state.index
        set(value) { state.index = value }
    private var run: Long
        get() = state.run
        set(value) { state.run = value }
    private var skipped: Int
        get() = state.skipped
        set(value) { state.skipped = value }
    private var foreground = true
    private var closed = false
    private var dialog: AlertDialog? = null
    private var highlighted: View? = null
    private var oldForeground: Drawable? = null
    private var oldFocusableInTouchMode = false
    private lateinit var summary: L7SettingRow
    private lateinit var next: L7SettingRow
    private lateinit var help: L7SettingRow
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val active get() = index in steps.indices

    init {
        if (index !in -1..steps.size) index = -1
        require(kind.matches(Regex("[A-Z_]{1,24}")))
        L7SettingsSection.add(parent, text(R.string.debug_guide_title)) { card ->
            summary = L7SettingRow(activity, text(R.string.debug_guide_title)).also(card::addView)
            next = L7Components.actionRow(activity, text(R.string.debug_guide_begin)) {
                if (index == -1 || index == steps.size) begin() else advance(false)
            }.also(card::addView)
            help = L7Components.actionRow(activity, text(R.string.debug_guide_help)) { show() }.also(card::addView)
        }
        handler.post { if (!closed) update() }
    }
    private fun text(id: Int) = activity.getString(id)
    private fun begin() {
        if (closed || !foreground) return
        run = runs.updateAndGet { maxOf(it + 1, android.os.SystemClock.elapsedRealtime()) }; skipped = 0; index = 0
        enter(); show()
    }
    private fun enter() {
        clearHighlight()
        steps[index].enter()
        event("ENTER")
        update()
    }
    private fun advance(missing: Boolean) {
        if (!active || closed || !foreground || !missing && !steps[index].ready()) return
        event(if (missing) "MISSING" else "COMPLETED")
        if (missing) skipped++
        clearHighlight(); dialog?.dismiss(); dialog = null
        index++
        if (active) { enter(); handler.post { if (foreground && !closed) show() } }
        else { event("FINISHED"); update() }
    }
    val currentStep get() = index
    val isPromptShowing get() = dialog?.isShowing == true
    fun missing() { advance(true) }
    fun reset() { dialog?.dismiss(); dialog = null; clearHighlight(); index = -1; update() }
    fun update() {
        if (closed) return
        val step = steps.getOrNull(index)
        summary.setValue(when {
            step != null -> activity.getString(R.string.debug_guide_progress, index + 1, steps.size, text(step.title))
            index == steps.size -> activity.getString(R.string.debug_guide_finished, skipped)
            else -> text(R.string.debug_guide_intro)
        })
        summary.setDescription(step?.let { text(it.body) }.orEmpty())
        summary.setFeedback(if (step != null && !step.ready()) text(R.string.debug_guide_wait) else "")
        val nextTitle = text(if (active) R.string.debug_guide_next else if (index == steps.size) R.string.debug_guide_again else R.string.debug_guide_begin)
        if (next.titleView.text.toString() != nextTitle) next.titleView.text = nextTitle
        next.isEnabled = foreground && (!active || step?.ready() == true)
        help.visibility = if (active) View.VISIBLE else View.GONE
        help.isEnabled = foreground
    }
    private fun show() {
        if (!active || closed || !foreground || activity.isFinishing || activity.isDestroyed) return
        dialog?.dismiss()
        val expected = index
        val step = steps[index]
        dialog = L7Dialogs.builder(activity)
            .setTitle(activity.getString(R.string.debug_guide_progress, index + 1, steps.size, text(step.title)))
            .setMessage(text(step.body) + "\n\n" + text(R.string.debug_guide_wait))
            .setPositiveButton(if (step.ready()) R.string.debug_guide_next else R.string.debug_guide_locate) { clicked, _ ->
                if (dialog === clicked && clicked.isShowing && expected == index && !closed && foreground) {
                    if (step.ready()) advance(false) else handler.post { if (!closed && foreground && expected == index) locate(step.target()) }
                }
            }
            .setNeutralButton(R.string.debug_guide_missing) { clicked, _ ->
                if (dialog === clicked && clicked.isShowing && expected == index) advance(true)
            }
            .setNegativeButton(R.string.debug_guide_hide, null)
            .setOnDismissListener { dismissed -> if (dialog === dismissed) dialog = null }
            .show()
    }
    private fun locate(view: View?) {
        clearHighlight()
        if (view == null || view.visibility != View.VISIBLE) return
        highlighted = view; oldForeground = view.foreground
        oldFocusableInTouchMode = view.isFocusableInTouchMode
        view.foreground = GradientDrawable().apply {
            cornerRadius = 8 * view.resources.displayMetrics.density
            setStroke((3 * view.resources.displayMetrics.density).toInt(), activity.getColor(R.color.product_ui_accent))
        }
        view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height), false)
        view.isFocusableInTouchMode = true
        view.requestFocus()
        view.announceForAccessibility(text(steps[index].body))
        event("FOCUSED")
    }
    private fun clearHighlight() {
        highlighted?.let { it.foreground = oldForeground; it.isFocusableInTouchMode = oldFocusableInTouchMode }
        highlighted = null; oldForeground = null
    }
    private fun event(phase: String) {
        val numeric = runCatching { evidence() }.getOrDefault(longArrayOf()).take(4).mapIndexed { i, value -> "evidence$i=$value" }.joinToString(" ")
        runCatching { L7DebugLog.record("DEBUG_GUIDE run=$run kind=$kind step=${index + 1} phase=$phase monoMs=${android.os.SystemClock.elapsedRealtime()} skipped=$skipped $numeric") }
    }
    fun background() {
        if (active && foreground) event("PAUSED")
        foreground = false; dialog?.dismiss(); dialog = null; clearHighlight(); update()
    }
    fun resume() { if (!closed) { foreground = true; update() } }
    override fun close() { background(); closed = true }
    private companion object { val runs = AtomicLong() }
}
