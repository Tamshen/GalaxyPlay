package com.shilapi.xcertplay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.shilapi.xcertplay.host.R

/** 正文独立滚动，确认和退出始终可见；到达文末不会自动勾选。 */
internal class L7AgreementPanel(
    context: Context,
    document: String,
    private val reviewing: Boolean,
    private val onAccept: () -> Unit,
    onLeave: () -> Unit,
    onRevoke: () -> Unit,
) : LinearLayout(context) {
    internal val scroll = ScrollView(context)
    internal val check = CheckBox(context)
    internal val accept = L7Components.actionButton(context, context.getString(R.string.l7_agreement_accept), true) {
        if (ready && readingActive && reachedEnd && waitComplete && check.isChecked) onAccept()
    }
    private val hint = L7Components.text(context, "", secondary = true)
    private var ready = false
    private var reachedEnd = false
    private var readingActive = false
    private var waitComplete = false
    private var waitUntil: Long? = null
    private val countdownHandler = Handler(Looper.getMainLooper())
    private val countdown = Runnable { refreshActions() }

    init {
        orientation = VERTICAL
        setPadding(dp(24), dp(24), dp(24), dp(24))
        L7Ui.bind(this) { setBackgroundColor(context.getColor(R.color.product_ui_background)) }
        val panel = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(32), dp(28), dp(32), dp(24))
            L7Ui.surface(this, radius = 8)
        }
        addView(panel, LayoutParams(-1, -1))
        panel.addView(header(), LayoutParams(-1, -2))
        panel.addView(L7Components.text(context, context.getString(R.string.l7_agreement_safe_hint), true).apply {
            gravity = Gravity.CENTER
        }, LayoutParams(-1, -2).apply { topMargin = dp(12); bottomMargin = dp(28) })
        val body = L7Components.text(context, "").apply {
            textSize = 20f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setLineSpacing(dp(6).toFloat(), 1f)
            setPadding(0, 0, dp(8), dp(16))
            // 主题刷新时同步正文中的强调色，保持原有滚动容器和主动确认状态。
            L7Ui.bind(this) {
                setTextColor(context.getColor(R.color.product_ui_text))
                text = formatted(document)
            }
        }
        scroll.apply {
            isFillViewport = true
            addView(body)
            setOnScrollChangeListener { _, _, _, _, _ -> updateReadState() }
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> post { updateReadState() } }
        }
        panel.addView(scroll, LayoutParams(-1, 0, 1f))
        panel.addView(hint, LayoutParams(-1, -2).apply { topMargin = dp(24) })
        check.apply {
            text = context.getString(R.string.l7_agreement_check)
            textSize = 18f
            minHeight = dp(64)
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            setPaddingRelative(0, dp(12), 0, dp(12))
            compoundDrawablePadding = dp(12)
            isChecked = false
            isEnabled = false
            L7Ui.bind(this) {
                setTextColor(ColorStateList(
                    arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                    intArrayOf(context.getColor(R.color.product_ui_muted), context.getColor(R.color.product_ui_text))))
                buttonTintList = null
                buttonDrawable = StateListDrawable().apply {
                    addState(intArrayOf(-android.R.attr.state_enabled),
                        context.getDrawable(R.drawable.ic_l7_checkbox_off)?.mutate()?.apply { alpha = 100 })
                    addState(intArrayOf(android.R.attr.state_checked), context.getDrawable(R.drawable.ic_l7_checkbox_on))
                    addState(intArrayOf(), context.getDrawable(R.drawable.ic_l7_checkbox_off))
                }
            }
            setOnCheckedChangeListener { _, _ -> refreshActions() }
            visibility = if (reviewing) View.GONE else View.VISIBLE
        }
        panel.addView(check, LayoutParams(-1, -2).apply { topMargin = dp(4) })
        val actions = LinearLayout(context).apply {
            orientation = if (resources.configuration.fontScale >= 1.3f || resources.configuration.screenWidthDp < 600)
                VERTICAL else HORIZONTAL
        }
        val leave = L7Components.actionButton(context,
            context.getString(if (reviewing) R.string.l7_agreement_back else R.string.l7_agreement_decline), click = onLeave)
        val primary = if (reviewing) L7Components.actionButton(context,
            context.getString(R.string.l7_agreement_revoke), click = onRevoke) else accept
        listOf(leave, primary).forEachIndexed { index, button ->
            L7Ui.button(button, primary = !reviewing && button === accept)
            val params = if (actions.orientation == HORIZONTAL) LayoutParams(0, -2, 1f).apply {
                if (index > 0) marginStart = dp(16)
            } else LayoutParams(-1, -2).apply { if (index > 0) topMargin = dp(12) }
            actions.addView(button, params)
        }
        panel.addView(actions, LayoutParams(-1, -2).apply { topMargin = dp(24) })
        refreshActions()
    }

    fun setReady(value: Boolean) {
        ready = value
        if (!value && !waitComplete) waitUntil = null
        refreshActions()
    }

    fun setReadingActive(value: Boolean) {
        readingActive = value
        // 未完成的等待不能在后台走完；返回协议后重新计满五秒。
        if (!value && !waitComplete) waitUntil = null
        refreshActions()
    }

    override fun onDetachedFromWindow() {
        setReadingActive(false)
        super.onDetachedFromWindow()
    }

    private fun header() = LinearLayout(context).apply {
        gravity = Gravity.CENTER
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_l7_agreement)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            L7Ui.bind(this) { imageTintList = ColorStateList.valueOf(context.getColor(R.color.product_ui_text)) }
        }, LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })
        addView(L7Components.text(context, context.getString(R.string.l7_agreement_title)).apply {
            textSize = 28f
            gravity = Gravity.CENTER
            includeFontPadding = false
        }, LayoutParams(-2, -2))
    }

    private fun updateReadState() {
        if (scroll.height > 0 && scroll.getChildAt(0).height > 0 && !scroll.canScrollVertically(1)) reachedEnd = true
        refreshActions()
    }

    private fun refreshActions() {
        countdownHandler.removeCallbacks(countdown)
        val now = SystemClock.elapsedRealtime()
        if (!reviewing && ready && readingActive && reachedEnd && !waitComplete && waitUntil == null) {
            waitUntil = now + 5_000L
        }
        val remaining = waitUntil?.let { (it - now).coerceAtLeast(0L) } ?: 5_000L
        if (readingActive && waitUntil != null && remaining == 0L) waitComplete = true
        check.isEnabled = ready && readingActive && reachedEnd && waitComplete
        accept.isEnabled = check.isEnabled && check.isChecked
        hint.text = when {
            reviewing -> context.getString(R.string.l7_agreement_accepted)
            !ready -> context.getString(R.string.l7_agreement_stopping)
            !reachedEnd -> context.getString(R.string.l7_agreement_read_end)
            !waitComplete -> context.getString(R.string.l7_agreement_countdown, (remaining + 999L) / 1000L)
            else -> context.getString(R.string.l7_agreement_confirm_hint)
        }
        if (readingActive && ready && waitUntil != null && !waitComplete) {
            countdownHandler.postDelayed(countdown, remaining.coerceAtMost(1000L))
        }
    }

    private fun dp(value: Int) = L7Components.dp(context, value)

    private fun formatted(markdown: String): CharSequence {
        val result = SpannableStringBuilder()
        // 标题已在固定页首展示，正文不再重复；原始协议资产及同意摘要保持不变。
        markdown.lineSequence().dropWhile { it.startsWith("# ") || it.isBlank() }.forEach { line ->
            val heading = line.startsWith("#")
            val start = result.length
            val plain = if (heading) line.trimStart('#', ' ') else line.trimEnd()
            var cursor = 0
            Regex("\\*\\*(.+?)\\*\\*").findAll(plain).forEach { match ->
                result.append(plain.substring(cursor, match.range.first))
                val boldStart = result.length
                result.append(match.groupValues[1])
                result.setSpan(StyleSpan(Typeface.BOLD), boldStart, result.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                result.setSpan(ForegroundColorSpan(context.getColor(R.color.product_ui_danger)),
                    boldStart, result.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                cursor = match.range.last + 1
            }
            result.append(plain.substring(cursor))
            if (heading) result.setSpan(StyleSpan(Typeface.BOLD), start, result.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            result.append('\n')
        }
        return result
    }
}
