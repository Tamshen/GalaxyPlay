package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.shilapi.xcertplay.host.R
import java.util.concurrent.Executors

/** 简易 LogView：后台读取快照，虚拟列表按行展示，搜索和复制不触发上传。 */
internal class L7LogView(private val context: Context) {
    private val model = L7LogViewModel()
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "l7-log-view").apply { isDaemon = true } }
    private var closed = false
    private var loading = false
    private var copyDialog: AlertDialog? = null
    private val summary = L7Components.text(context, "", secondary = true)
    private val copyMatches = L7Components.actionButton(context, context.getString(R.string.l7_log_copy_matches)) { copy(true) }
    private val search = EditText(context).apply {
        hint = context.getString(R.string.l7_log_search)
        contentDescription = hint
        setSingleLine()
        isSaveEnabled = false
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
    }
    private val adapter = object : BaseAdapter() {
        override fun getCount() = model.matches.size
        override fun getItem(position: Int) = model.matches[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, recycled: View?, parent: ViewGroup): View {
            val text = recycled as? TextView ?: L7Components.text(context, "").apply {
                typeface = Typeface.MONOSPACE
                textSize = 14f
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }
            val line = getItem(position)
            text.text = SpannableString(line).apply {
                if (model.query.isNotEmpty()) {
                    var index = line.indexOf(model.query, ignoreCase = true)
                    while (index >= 0) {
                        setSpan(BackgroundColorSpan(context.getColor(R.color.product_ui_selected)), index,
                            index + model.query.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        index = line.indexOf(model.query, index + model.query.length, ignoreCase = true)
                    }
                }
            }
            L7Ui.refresh(text)
            return text
        }
    }
    private val list = ListView(context).apply {
        adapter = this@L7LogView.adapter
        divider = null
        setOnItemLongClickListener { _, _, position, _ -> copyText(model.matches[position]); true }
        setOnTouchListener { view, event ->
            view.parent.requestDisallowInterceptTouchEvent(event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL)
            false
        }
    }
    private val filter = Runnable { model.search(search.text.toString()); render() }
    private val body = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(search, LinearLayout.LayoutParams(-1, -2))
        addView(summary, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(8) })
        addView(copyMatches, LinearLayout.LayoutParams(-1, -2))
        val height = (context.resources.configuration.screenHeightDp * .38f).toInt().coerceIn(160, 420)
        addView(list, LinearLayout.LayoutParams(-1, dp(height)).apply { topMargin = dp(12) })
    }
    private val dialog = L7Dialogs.builder(context).setTitle(R.string.l7_debug_view).setView(body)
        .setPositiveButton(R.string.close, null).setNeutralButton(R.string.l7_debug_refresh, null)
        .setNegativeButton(R.string.l7_log_copy_all, null).setOnDismissListener { release() }.create()

    init {
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                main.removeCallbacks(filter); main.postDelayed(filter, 150)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { refresh() }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { copy(false) }
            render(); refresh()
        }
    }

    fun show() { dialog.show(); search.clearFocus() }
    fun close() { dialog.dismiss(); release() }
    private fun release() {
        closed = true
        main.removeCallbacksAndMessages(null)
        copyDialog?.dismiss()
        worker.shutdownNow()
        model.replace(emptyList())
    }
    private fun dp(value: Int) = L7Components.dp(context, value)

    private fun refresh() {
        if (closed || loading) return
        loading = true
        summary.setText(R.string.l7_log_loading)
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = false
        val app = context.applicationContext
        worker.execute {
            val result = runCatching { RemoteLogReport.collect(app).batches.flatMap { batch ->
                batch.entries.map { "[${it.source}] ${it.message}" }
            } }
            main.post {
                if (closed) return@post
                loading = false
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = true
                result.onSuccess { model.replace(it); model.search(search.text.toString()); render() }
                    .onFailure { summary.setText(R.string.l7_log_read_failed) }
            }
        }
    }

    private fun render() {
        summary.text = context.getString(R.string.l7_log_match_count, model.matches.size, model.lines.size)
        copyMatches.isEnabled = model.matches.isNotEmpty()
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = model.lines.isNotEmpty()
        adapter.notifyDataSetChanged()
    }

    private fun copy(filtered: Boolean) {
        // 输入刚变化时也复制最新筛选条件，不等待防抖计时。
        model.search(search.text.toString())
        val parts = model.copyParts(filtered)
        if (parts.size <= 1) { parts.firstOrNull()?.let(::copyText); return }
        copyDialog?.dismiss()
        copyDialog = L7Dialogs.builder(context).setTitle(R.string.l7_log_copy_parts)
            .setMessage(R.string.l7_log_copy_parts_hint)
            .setItems(parts.indices.map { context.getString(R.string.l7_log_copy_part, it + 1, parts.size) }.toTypedArray()) { _, index ->
                copyText(parts[index])
            }.setNegativeButton(R.string.close, null).show()
    }

    private fun copyText(value: String) {
        val success = runCatching { context.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(context.getString(R.string.l7_debug_view), value)) }.isSuccess
        Toast.makeText(context, if (success) R.string.l7_debug_copied else R.string.l7_log_copy_failed, Toast.LENGTH_SHORT).show()
    }
}
